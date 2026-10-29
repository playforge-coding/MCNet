use std::os::raw::c_int;
use std::ptr;
use std::sync::{Arc, Mutex};

use yuv::{YuvPlanarImage, YuvRange, YuvStandardMatrix};

use crate::VideoError;
use crate::frame::{i420_to_rgba, rgba_target};
use crate::sys::*;

/// A dav1d AV1 decoder tuned for latency: one frame of delay, so a picture
/// comes out of [decode] for the packet that went in.
#[derive(uniffi::Object)]
pub struct Decoder {
    ctx: Mutex<Context>,
}

struct Context(*mut Dav1dContext);

// Only ever touched while holding the mutex.
unsafe impl Send for Context {}

/// One decoded picture, still in dav1d's reference-counted buffers. Kotlin
/// asks for it as RGBA once it knows where the pixels should go.
#[derive(uniffi::Object)]
pub struct Picture {
    picture: Dav1dPicture,
}

// dav1d's picture references are atomic and the planes are never written
// after decoding, so sharing a picture between threads is safe.
unsafe impl Send for Picture {}
unsafe impl Sync for Picture {}

#[uniffi::export]
impl Decoder {
    #[uniffi::constructor]
    pub fn new(threads: u32) -> Result<Arc<Self>, VideoError> {
        unsafe {
            let mut settings = Dav1dSettings::default();
            dav1d_default_settings(&mut settings);
            settings.n_threads = threads as c_int;
            // The default lets dav1d buffer several frames for throughput.
            // A call cannot afford that.
            settings.max_frame_delay = 1;
            let mut ctx = ptr::null_mut();
            check(dav1d_open(&mut ctx, &settings))?;
            Ok(Arc::new(Decoder {
                ctx: Mutex::new(Context(ctx)),
            }))
        }
    }

    /// Feeds one packet and returns the newest picture it produced, if any.
    pub fn decode(&self, packet: Vec<u8>) -> Result<Option<Arc<Picture>>, VideoError> {
        let ctx = self.ctx.lock().unwrap();
        unsafe {
            let mut data = Dav1dData::default();
            let buffer = dav1d_data_create(&mut data, packet.len());
            if buffer.is_null() {
                return Err(VideoError::Decoder("out of memory".into()));
            }
            ptr::copy_nonoverlapping(packet.as_ptr(), buffer, packet.len());

            let mut latest = None;
            let result = (|| {
                loop {
                    let sent = dav1d_send_data(ctx.0, &mut data);
                    if sent < 0 && sent != again() {
                        return check(sent);
                    }
                    loop {
                        let mut picture = Dav1dPicture::default();
                        let got = dav1d_get_picture(ctx.0, &mut picture);
                        if got == again() {
                            break;
                        }
                        check(got)?;
                        // Dropping an older picture here releases its buffers.
                        latest = Some(Arc::new(Picture { picture }));
                    }
                    if data.sz == 0 {
                        return Ok(());
                    }
                }
            })();
            if data.sz > 0 {
                dav1d_data_unref(&mut data);
            }
            result.map(|()| latest)
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        let ctx = self.ctx.get_mut().unwrap();
        unsafe { dav1d_close(&mut ctx.0) };
    }
}

#[uniffi::export]
impl Picture {
    pub fn width(&self) -> u32 {
        self.picture.p.w as u32
    }

    pub fn height(&self) -> u32 {
        self.picture.p.h as u32
    }

    /// Converts to 8-bit RGBA, tightly packed, and writes it to native memory
    /// at [address]. This is how pixels reach a texture without passing
    /// through the Java heap: the caller hands over the address of an
    /// off-heap buffer (Minecraft's NativeImage).
    ///
    /// The caller guarantees [address] points at [capacity] writable bytes.
    pub fn write_rgba(&self, address: u64, capacity: u64) -> Result<(), VideoError> {
        let target = rgba_target(address, capacity, self.width(), self.height())?;
        self.convert(target)
    }

    /// The same as [write_rgba], into a fresh heap array. For tests, not the
    /// per-frame path.
    pub fn rgba(&self) -> Result<Vec<u8>, VideoError> {
        let mut out = vec![0u8; self.width() as usize * self.height() as usize * 4];
        self.convert(&mut out)?;
        Ok(out)
    }
}

impl Picture {
    fn convert(&self, target: &mut [u8]) -> Result<(), VideoError> {
        let p = &self.picture.p;
        if p.bpc != 8 || p.layout != DAV1D_PIXEL_LAYOUT_I420 {
            return Err(VideoError::Decoder(format!(
                "unsupported picture: {} bit, layout {}",
                p.bpc, p.layout
            )));
        }
        let (w, h) = (p.w as usize, p.h as usize);
        let (y_stride, uv_stride) = (
            self.picture.stride[0] as usize,
            self.picture.stride[1] as usize,
        );
        let (cw, ch) = (w.div_ceil(2), h.div_ceil(2));
        let plane = |index: usize, stride: usize, width: usize, rows: usize| unsafe {
            std::slice::from_raw_parts(
                self.picture.data[index] as *const u8,
                stride * (rows - 1) + width,
            )
        };
        let image = YuvPlanarImage {
            y_plane: plane(0, y_stride, w, h),
            y_stride: y_stride as u32,
            u_plane: plane(1, uv_stride, cw, ch),
            u_stride: uv_stride as u32,
            v_plane: plane(2, uv_stride, cw, ch),
            v_stride: uv_stride as u32,
            width: w as u32,
            height: h as u32,
        };

        let header = unsafe { &*self.picture.seq_hdr };
        let range = if header.color_range != 0 {
            YuvRange::Full
        } else {
            YuvRange::Limited
        };
        let matrix = if header.mtrx == DAV1D_MC_BT601 {
            YuvStandardMatrix::Bt601
        } else {
            YuvStandardMatrix::Bt709
        };
        i420_to_rgba(&image, target, range, matrix)
    }
}

impl Drop for Picture {
    fn drop(&mut self) {
        unsafe { dav1d_picture_unref(&mut self.picture) };
    }
}

/// dav1d reports "try again" as a negated errno, which differs by platform.
fn again() -> c_int {
    -(EAGAIN as c_int)
}

fn check(result: c_int) -> Result<(), VideoError> {
    if result >= 0 {
        return Ok(());
    }
    Err(VideoError::Decoder(
        std::io::Error::from_raw_os_error(-result).to_string(),
    ))
}
