//! Linux capture through Video4Linux2, using the `v4l` crate for the ioctl
//! plumbing and memory-mapped buffers.
//!
//! V4L2 hands over whatever the camera produces. NV12 and YUYV are converted
//! directly; MJPEG, which is what most webcams offer above 480p, is decoded
//! in Rust first. The driver cannot scale, so the smallest mode that covers
//! the target height is chosen and the frame is box-filtered down.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::thread::JoinHandle;
use std::time::Duration;

use v4l::buffer::Type;
use v4l::capability::Flags;
use v4l::frameinterval::FrameIntervalEnum;
use v4l::framesize::FrameSizeEnum;
use v4l::io::mmap::Stream;
use v4l::io::traits::CaptureStream;
use v4l::video::Capture;
use v4l::{Device, Format, FourCC};
use zune_jpeg::JpegDecoder;
use zune_jpeg::zune_core::colorspace::ColorSpace;
use zune_jpeg::zune_core::options::DecoderOptions;

use super::{CameraInfo, Sink};
use crate::VideoError;
use crate::frame::Frame;

/// Pixel formats we can turn into I420, best first. MJPEG is last because
/// it costs a JPEG decode per frame.
const ACCEPTED: [&[u8; 4]; 3] = [b"NV12", b"YUYV", b"MJPG"];

pub(crate) struct Session {
    running: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    source: String,
    width: u32,
    height: u32,
}

impl Session {
    pub(crate) fn source(&self) -> String {
        self.source.clone()
    }

    pub(crate) fn width(&self) -> u32 {
        self.width
    }

    pub(crate) fn height(&self) -> u32 {
        self.height
    }

    pub(crate) fn stop(mut self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

pub(crate) fn permission() -> bool {
    true
}

pub(crate) fn request_permission(_timeout: Duration) -> bool {
    true
}

pub(crate) fn devices() -> Result<Vec<CameraInfo>, VideoError> {
    let mut cameras = Vec::new();
    for node in v4l::context::enum_devices() {
        // Every UVC camera also exposes a metadata node that opens fine but
        // captures nothing; the capability flag tells them apart.
        let Ok(device) = Device::with_path(node.path()) else {
            continue;
        };
        let Ok(caps) = device.query_caps() else {
            continue;
        };
        if !caps.capabilities.contains(Flags::VIDEO_CAPTURE)
            || device.enum_formats().map(|f| f.is_empty()).unwrap_or(true)
        {
            continue;
        }
        cameras.push(CameraInfo {
            id: node.path().to_string_lossy().into_owned(),
            name: node.name().unwrap_or_else(|| caps.card.clone()),
            model: format!("{} on {}", caps.driver, caps.bus),
        });
    }
    Ok(cameras)
}

pub(crate) fn open(
    id: &str,
    target_height: u32,
    fps: u32,
    sink: Sink,
) -> Result<Session, VideoError> {
    let (ready_tx, ready_rx) = mpsc::channel();
    let running = Arc::new(AtomicBool::new(true));
    let id = id.to_string();
    let thread = {
        let running = running.clone();
        std::thread::Builder::new()
            .name("mcnet-video-camera".into())
            .spawn(move || run(&id, target_height, fps, sink, ready_tx, running))
            .map_err(|e| VideoError::Camera(e.to_string()))?
    };
    let opened = ready_rx
        .recv_timeout(Duration::from_secs(10))
        .map_err(|_| VideoError::Camera("the camera did not start within 10 seconds".into()))??;
    Ok(Session {
        running,
        thread: Some(thread),
        source: opened.source,
        width: opened.width,
        height: opened.height,
    })
}

struct Opened {
    source: String,
    width: u32,
    height: u32,
}

fn run(
    id: &str,
    target_height: u32,
    fps: u32,
    sink: Sink,
    ready: mpsc::Sender<Result<Opened, VideoError>>,
    running: Arc<AtomicBool>,
) {
    let (device, format) = match start(id, target_height, fps) {
        Ok(started) => started,
        Err(e) => {
            let _ = ready.send(Err(e));
            return;
        }
    };
    let mut stream = match Stream::with_buffers(&device, Type::VideoCapture, 4) {
        Ok(stream) => stream,
        Err(e) => {
            let _ = ready.send(Err(VideoError::Camera(e.to_string())));
            return;
        }
    };
    let factor = Sink::downscale_factor(format.height, target_height);
    let _ = ready.send(Ok(Opened {
        source: format!("{}x{} {}", format.width, format.height, format.fourcc),
        width: (format.width / factor) & !1,
        height: (format.height / factor) & !1,
    }));

    while running.load(Ordering::Relaxed) {
        let Ok((buffer, meta)) = stream.next() else {
            break;
        };
        if !sink.wants_frame() {
            continue;
        }
        let data = &buffer[..(meta.bytesused as usize).min(buffer.len())];
        if let Ok(frame) = convert(&format, data) {
            sink.offer(frame);
        }
    }
}

fn start(id: &str, target_height: u32, fps: u32) -> Result<(Device, Format), VideoError> {
    let device = Device::with_path(id).map_err(|e| VideoError::Camera(format!("{id}: {e}")))?;
    let fps_penalty = |rate: u32| {
        if rate >= fps {
            rate - fps
        } else {
            (fps - rate) * 100
        }
    };

    // Every usable mode, as (fourcc rank, width, height, fps).
    let mut modes = Vec::new();
    for (rank, fourcc) in ACCEPTED.iter().enumerate() {
        let fourcc = FourCC::new(fourcc);
        for size in device.enum_framesizes(fourcc).unwrap_or_default() {
            let (width, height) = match size.size {
                FrameSizeEnum::Discrete(d) => (d.width, d.height),
                // A scaler-backed camera: ask for the target at 16:9, within
                // what it allows.
                FrameSizeEnum::Stepwise(s) => (
                    (target_height * 16 / 9).clamp(s.min_width, s.max_width) & !1,
                    target_height.clamp(s.min_height, s.max_height) & !1,
                ),
            };
            let rates = device
                .enum_frameintervals(fourcc, width, height)
                .unwrap_or_default()
                .into_iter()
                .map(|interval| match interval.interval {
                    FrameIntervalEnum::Discrete(f) => f.denominator / f.numerator.max(1),
                    FrameIntervalEnum::Stepwise(s) => s.min.denominator / s.min.numerator.max(1),
                })
                .collect::<Vec<_>>();
            for rate in if rates.is_empty() { vec![fps] } else { rates } {
                modes.push((rank, fourcc, width, height, rate));
            }
        }
    }
    let chosen = modes
        .iter()
        .filter(|(_, _, _, h, _)| *h >= target_height)
        .min_by_key(|(rank, _, w, h, rate)| (w * h, fps_penalty(*rate), *rank))
        .or_else(|| {
            modes.iter().max_by_key(|(rank, _, w, h, rate)| {
                (w * h, u32::MAX - fps_penalty(*rate), usize::MAX - rank)
            })
        })
        .ok_or_else(|| {
            VideoError::Camera("the camera offers no pixel format we can convert".into())
        })?;

    let format = device
        .set_format(&Format::new(chosen.2, chosen.3, chosen.1))
        .map_err(|e| VideoError::Camera(e.to_string()))?;
    if !ACCEPTED.contains(&&format.fourcc.repr) {
        return Err(VideoError::Camera(format!(
            "the camera insisted on {}",
            format.fourcc
        )));
    }
    let mut params = device
        .params()
        .map_err(|e| VideoError::Camera(e.to_string()))?;
    params.interval = v4l::Fraction::new(1, chosen.4);
    // A camera that refuses the rate still works; it just runs at its own.
    let _ = device.set_params(&params);
    Ok((device, format))
}

fn convert(format: &Format, data: &[u8]) -> Result<Frame, VideoError> {
    let (w, h) = (format.width, format.height);
    let stride = |min: usize| {
        if format.stride as usize >= min {
            format.stride as usize
        } else {
            min
        }
    };
    match &format.fourcc.repr {
        b"NV12" => {
            let stride = stride(w as usize);
            let luma = stride * h as usize;
            if data.len() < luma {
                return Err(VideoError::Camera("short NV12 frame".into()));
            }
            Frame::from_nv12(w, h, &data[..luma], stride, &data[luma..], stride)
        }
        b"YUYV" => Frame::from_yuyv(w, h, data, stride(w as usize * 2)),
        b"MJPG" => {
            let options = DecoderOptions::default().jpeg_set_out_colorspace(ColorSpace::RGB);
            let mut decoder = JpegDecoder::new_with_options(std::io::Cursor::new(data), options);
            let rgb = decoder
                .decode()
                .map_err(|e| VideoError::Camera(e.to_string()))?;
            let info = decoder
                .info()
                .ok_or_else(|| VideoError::Camera("JPEG without a size".into()))?;
            Frame::from_rgb(
                info.width as u32,
                info.height as u32,
                &rgb,
                info.width as usize * 3,
            )
        }
        other => Err(VideoError::Camera(format!(
            "unexpected pixel format {}",
            String::from_utf8_lossy(other)
        ))),
    }
}
