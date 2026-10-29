//! Windows capture through Media Foundation's source reader.
//!
//! The reader is asked for NV12 with video processing enabled, so Media
//! Foundation inserts whatever converter the camera's native format needs
//! (including the MJPEG decoder most webcams need above 480p). It cannot
//! scale, so the smallest mode that covers the target height is chosen and
//! the frame is box-filtered down from there.
//!
//! Everything happens on one thread, from `CoInitializeEx` to the last
//! `ReadSample`, which keeps the COM apartment rules simple.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::thread::JoinHandle;
use std::time::Duration;

use windows::Win32::Media::MediaFoundation::{
    IMFActivate, IMFAttributes, IMFMediaSource, IMFSample, IMFSourceReader,
    MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID,
    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, MF_MT_DEFAULT_STRIDE,
    MF_MT_FRAME_RATE, MF_MT_FRAME_SIZE, MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE,
    MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, MF_SOURCE_READER_FIRST_VIDEO_STREAM,
    MF_SOURCE_READERF_ENDOFSTREAM, MF_VERSION, MFCreateAttributes, MFCreateMediaType,
    MFCreateSourceReaderFromMediaSource, MFEnumDeviceSources, MFMediaType_Video,
    MFSTARTUP_NOSOCKET, MFStartup, MFVideoFormat_NV12,
};
use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx, CoTaskMemFree};
use windows::core::{GUID, PWSTR};

use super::{CameraInfo, Sink};
use crate::VideoError;
use crate::frame::Frame;

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
            // ReadSample returns at the next frame, so this waits at most one
            // frame interval.
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
    init()?;
    Ok(activates()?
        .iter()
        .filter_map(|activate| {
            Some(CameraInfo {
                id: string(
                    activate,
                    &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK,
                )?,
                name: string(activate, &MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME)?,
                model: String::new(),
            })
        })
        .collect())
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

/// The whole life of a capture, on its own thread.
fn run(
    id: &str,
    target_height: u32,
    fps: u32,
    sink: Sink,
    ready: mpsc::Sender<Result<Opened, VideoError>>,
    running: Arc<AtomicBool>,
) {
    let (reader, mode) = match start(id, target_height, fps) {
        Ok(started) => started,
        Err(e) => {
            let _ = ready.send(Err(e));
            return;
        }
    };
    let factor = Sink::downscale_factor(mode.height, target_height);
    let _ = ready.send(Ok(Opened {
        source: format!("{}x{} @ {} fps", mode.width, mode.height, mode.fps),
        width: (mode.width / factor) & !1,
        height: (mode.height / factor) & !1,
    }));

    let stream = MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32;
    while running.load(Ordering::Relaxed) {
        let mut flags = 0u32;
        let mut sample: Option<IMFSample> = None;
        let read = unsafe {
            reader.ReadSample(stream, 0, None, Some(&mut flags), None, Some(&mut sample))
        };
        if read.is_err() || flags & MF_SOURCE_READERF_ENDOFSTREAM.0 as u32 != 0 {
            break;
        }
        // A read can legitimately return no sample, for instance on a format
        // change notification. Just ask again.
        let Some(sample) = sample else { continue };
        if !sink.wants_frame() {
            continue;
        }
        if let Some(frame) = unsafe { copy_frame(&sample, &mode) } {
            sink.offer(frame);
        }
    }
}

struct Mode {
    width: u32,
    height: u32,
    fps: u32,
    stride: usize,
}

fn start(id: &str, target_height: u32, fps: u32) -> Result<(IMFSourceReader, Mode), VideoError> {
    init()?;
    let activate = activates()?
        .into_iter()
        .find(|a| {
            string(a, &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK).as_deref()
                == Some(id)
        })
        .ok_or_else(|| VideoError::Camera(format!("no camera with id {id}")))?;
    unsafe {
        let source: IMFMediaSource = activate.ActivateObject().map_err(camera_error)?;
        let attributes = attributes(1)?;
        attributes
            .SetUINT32(&MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)
            .map_err(camera_error)?;
        let reader =
            MFCreateSourceReaderFromMediaSource(&source, &attributes).map_err(camera_error)?;
        let stream = MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32;

        // Every mode the camera offers, as (type, width, height, fps).
        let mut modes = Vec::new();
        for index in 0.. {
            let Ok(media_type) = reader.GetNativeMediaType(stream, index) else {
                break;
            };
            let size = media_type
                .GetUINT64(&MF_MT_FRAME_SIZE)
                .map_err(camera_error)?;
            let rate = media_type
                .GetUINT64(&MF_MT_FRAME_RATE)
                .unwrap_or(30 << 32 | 1);
            let (num, den) = ((rate >> 32) as u32, (rate & 0xFFFF_FFFF) as u32);
            modes.push((
                media_type,
                (size >> 32) as u32,
                (size & 0xFFFF_FFFF) as u32,
                num / den.max(1),
            ));
        }
        let fps_penalty = |rate: u32| {
            if rate >= fps {
                rate - fps
            } else {
                (fps - rate) * 100
            }
        };
        let chosen = modes
            .iter()
            .filter(|(_, _, h, _)| *h >= target_height)
            .min_by_key(|(_, w, h, rate)| (w * h, fps_penalty(*rate)))
            .or_else(|| {
                modes
                    .iter()
                    .max_by_key(|(_, w, h, rate)| (w * h, u32::MAX - fps_penalty(*rate)))
            })
            .ok_or_else(|| VideoError::Camera("the camera offers no video modes".into()))?;
        reader
            .SetCurrentMediaType(stream, None, &chosen.0)
            .map_err(camera_error)?;

        let output = MFCreateMediaType().map_err(camera_error)?;
        output
            .SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
            .map_err(camera_error)?;
        output
            .SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)
            .map_err(camera_error)?;
        reader
            .SetCurrentMediaType(stream, None, &output)
            .map_err(|e| VideoError::Camera(format!("the camera cannot deliver NV12: {e}")))?;

        let current = reader.GetCurrentMediaType(stream).map_err(camera_error)?;
        let size = current.GetUINT64(&MF_MT_FRAME_SIZE).map_err(camera_error)?;
        let (width, height) = ((size >> 32) as u32, (size & 0xFFFF_FFFF) as u32);
        let stride = current
            .GetUINT32(&MF_MT_DEFAULT_STRIDE)
            .map(|s| s as i32)
            .unwrap_or(width as i32);
        if stride < 0 {
            return Err(VideoError::Camera("bottom-up NV12 is not supported".into()));
        }
        Ok((
            reader,
            Mode {
                width,
                height,
                fps: chosen.3,
                stride: stride as usize,
            },
        ))
    }
}

unsafe fn copy_frame(sample: &IMFSample, mode: &Mode) -> Option<Frame> {
    unsafe {
        let buffer = sample.ConvertToContiguousBuffer().ok()?;
        let mut data: *mut u8 = std::ptr::null_mut();
        let mut length = 0u32;
        buffer.Lock(&mut data, None, Some(&mut length)).ok()?;
        let (w, h) = (mode.width as usize, mode.height as usize);
        let frame = if (length as usize) < mode.stride * h * 3 / 2 {
            None
        } else {
            let bytes = std::slice::from_raw_parts(data, length as usize);
            let (y, uv) = bytes.split_at(mode.stride * h);
            Frame::from_nv12(w as u32, h as u32, y, mode.stride, uv, mode.stride).ok()
        };
        let _ = buffer.Unlock();
        frame
    }
}

fn init() -> Result<(), VideoError> {
    unsafe {
        // S_FALSE means the thread was already initialized, which is fine,
        // and RPC_E_CHANGED_MODE means it was initialized with the other
        // threading model, which is also fine for what we do here.
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).map_err(camera_error)
    }
}

fn attributes(size: u32) -> Result<IMFAttributes, VideoError> {
    let mut attributes = None;
    unsafe { MFCreateAttributes(&mut attributes, size).map_err(camera_error)? };
    attributes.ok_or_else(|| VideoError::Camera("MFCreateAttributes returned nothing".into()))
}

/// Every video capture device, as the activation objects Media Foundation
/// hands out.
fn activates() -> Result<Vec<IMFActivate>, VideoError> {
    unsafe {
        let attributes = attributes(1)?;
        attributes
            .SetGUID(
                &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID,
            )
            .map_err(camera_error)?;
        let mut list: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        MFEnumDeviceSources(&attributes, &mut list, &mut count).map_err(camera_error)?;
        // Take ownership of each reference, then free the array that held them.
        let items = (0..count as usize)
            .filter_map(|i| std::ptr::read(list.add(i)))
            .collect();
        CoTaskMemFree(Some(list as *const _));
        Ok(items)
    }
}

fn string(attributes: &IMFActivate, key: &GUID) -> Option<String> {
    unsafe {
        let mut value = PWSTR::null();
        let mut length = 0u32;
        attributes
            .GetAllocatedString(key, &mut value, &mut length)
            .ok()?;
        let text = value.to_string().ok();
        CoTaskMemFree(Some(value.0 as *const _));
        text
    }
}

fn camera_error(error: windows::core::Error) -> VideoError {
    VideoError::Camera(error.to_string())
}
