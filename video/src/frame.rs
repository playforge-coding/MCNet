use std::sync::Arc;
use std::time::Instant;

use yuv::{
    BufferStoreMut, YuvConversionMode, YuvPackedImage, YuvPlanarImage, YuvPlanarImageMut, YuvRange,
    YuvStandardMatrix, rgb_to_yuv420, yuv420_to_rgba, yuyv422_to_yuv420,
};

use crate::VideoError;

/// One uncompressed picture in 8-bit I420: a full-size luma plane followed by
/// two quarter-size chroma planes, each tightly packed. Width and height are
/// always even, so the chroma planes are exactly half in each direction.
///
/// Opaque to Kotlin on purpose. A 640x360 frame is 345 KB, and copying that
/// through the FFI 30 times a second would be pure waste when the only thing
/// the JVM does with it is pass it to the encoder.
#[derive(uniffi::Object)]
pub struct Frame {
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) y: Vec<u8>,
    pub(crate) u: Vec<u8>,
    pub(crate) v: Vec<u8>,
    captured_at: Instant,
}

#[uniffi::export]
impl Frame {
    /// A moving test card, for tests and for runs without a camera. [index]
    /// advances the motion, so consecutive frames give the encoder something
    /// to predict.
    #[uniffi::constructor]
    pub fn test_pattern(width: u32, height: u32, index: u32) -> Result<Arc<Self>, VideoError> {
        let mut frame = Frame::blank(width, height)?;
        let (w, h) = (width as usize, height as usize);
        let shift = index as usize * 4;
        for row in 0..h {
            for col in 0..w {
                // Diagonal bands plus a square that slides across, which
                // exercises both smooth gradients and hard edges.
                let band = ((col + row + shift) % 256) as u8;
                let square = (col + w - (shift % w)) % w < w / 6 && row > h / 3 && row < h * 2 / 3;
                frame.y[row * w + col] = if square { 235 } else { 16 + band / 2 };
            }
        }
        let (cw, ch) = (w / 2, h / 2);
        for row in 0..ch {
            for col in 0..cw {
                frame.u[row * cw + col] = (64 + (col * 128 / cw)) as u8;
                frame.v[row * cw + col] = (64 + (row * 128 / ch)) as u8;
            }
        }
        Ok(Arc::new(frame))
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    /// Time since the frame reached this library from the camera driver.
    pub fn age_micros(&self) -> u64 {
        self.captured_at.elapsed().as_micros() as u64
    }

    /// Converts to 8-bit RGBA, tightly packed, and writes it to native memory
    /// at [address], the same way [crate::Picture::write_rgba] does. This is
    /// how the sender gets a preview of its own camera.
    ///
    /// The caller guarantees [address] points at [capacity] writable bytes.
    pub fn write_rgba(&self, address: u64, capacity: u64) -> Result<(), VideoError> {
        let target = rgba_target(address, capacity, self.width, self.height)?;
        i420_to_rgba(
            &self.planes(),
            target,
            YuvRange::Limited,
            YuvStandardMatrix::Bt709,
        )
    }
}

impl Frame {
    pub(crate) fn blank(width: u32, height: u32) -> Result<Self, VideoError> {
        if width < 16 || height < 16 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(VideoError::Invalid(format!(
                "frame size must be even and at least 16x16, got {width}x{height}"
            )));
        }
        let luma = width as usize * height as usize;
        Ok(Frame {
            width,
            height,
            y: vec![0; luma],
            u: vec![128; luma / 4],
            v: vec![128; luma / 4],
            captured_at: Instant::now(),
        })
    }

    /// Bi-planar 4:2:0: a luma plane, then a half-height plane of interleaved
    /// U and V. Rows are usually padded, so each plane brings its own stride.
    pub(crate) fn from_nv12(
        width: u32,
        height: u32,
        y: &[u8],
        y_stride: usize,
        uv: &[u8],
        uv_stride: usize,
    ) -> Result<Self, VideoError> {
        let (w, h) = (width as usize & !1, height as usize & !1);
        if w < 16 || h < 16 {
            return Err(VideoError::Camera(format!(
                "frame too small: {width}x{height}"
            )));
        }
        if y_stride < w
            || uv_stride < w
            || y.len() < y_stride * (h - 1) + w
            || uv.len() < uv_stride * (h / 2 - 1) + w
        {
            return Err(VideoError::Camera(format!(
                "NV12 planes too small for {width}x{height}"
            )));
        }
        let mut frame = Frame::blank(w as u32, h as u32)?;
        for row in 0..h {
            frame.y[row * w..(row + 1) * w].copy_from_slice(&y[row * y_stride..row * y_stride + w]);
        }
        let cw = w / 2;
        for row in 0..h / 2 {
            let line = &uv[row * uv_stride..row * uv_stride + w];
            for col in 0..cw {
                frame.u[row * cw + col] = line[col * 2];
                frame.v[row * cw + col] = line[col * 2 + 1];
            }
        }
        Ok(frame)
    }

    /// Packed 4:2:2 in Y0 U Y1 V order, what V4L2 calls YUYV and Windows
    /// calls YUY2. [stride] is in bytes.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub(crate) fn from_yuyv(
        width: u32,
        height: u32,
        data: &[u8],
        stride: usize,
    ) -> Result<Self, VideoError> {
        let (w, h) = (width & !1, height & !1);
        if stride < w as usize * 2 || data.len() < stride * (h as usize - 1) + w as usize * 2 {
            return Err(VideoError::Camera(format!(
                "YUYV buffer too small for {width}x{height}"
            )));
        }
        let mut frame = Frame::blank(w, h)?;
        let packed = YuvPackedImage {
            yuy: data,
            yuy_stride: stride as u32,
            width: w,
            height: h,
        };
        yuyv422_to_yuv420(&mut frame.planes_mut(), &packed)
            .map_err(|e| VideoError::Camera(e.to_string()))?;
        Ok(frame)
    }

    /// 8-bit RGB, which is what a decoded MJPEG frame is. [stride] in bytes.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub(crate) fn from_rgb(
        width: u32,
        height: u32,
        data: &[u8],
        stride: usize,
    ) -> Result<Self, VideoError> {
        let (w, h) = (width & !1, height & !1);
        if stride < w as usize * 3 || data.len() < stride * (h as usize - 1) + w as usize * 3 {
            return Err(VideoError::Camera(format!(
                "RGB buffer too small for {width}x{height}"
            )));
        }
        let mut frame = Frame::blank(w, h)?;
        rgb_to_yuv420(
            &mut frame.planes_mut(),
            data,
            stride as u32,
            YuvRange::Limited,
            YuvStandardMatrix::Bt709,
            YuvConversionMode::Balanced,
        )
        .map_err(|e| VideoError::Camera(e.to_string()))?;
        Ok(frame)
    }

    /// Shrinks by a whole-number factor with a box filter. For the backends
    /// whose capture API cannot scale, this is what gets 1080p down to 360p.
    pub(crate) fn downscale(self, factor: u32) -> Self {
        if factor <= 1 {
            return self;
        }
        let width = (self.width / factor) & !1;
        let height = (self.height / factor) & !1;
        let f = factor as usize;
        let shrink = |plane: &[u8], src_w: usize, dst_w: usize, dst_h: usize| {
            let mut out = vec![0u8; dst_w * dst_h];
            let area = (f * f) as u32;
            for row in 0..dst_h {
                for col in 0..dst_w {
                    let mut sum = 0u32;
                    for dy in 0..f {
                        let base = (row * f + dy) * src_w + col * f;
                        sum += plane[base..base + f].iter().map(|&p| p as u32).sum::<u32>();
                    }
                    out[row * dst_w + col] = ((sum + area / 2) / area) as u8;
                }
            }
            out
        };
        let (sw, dw, dh) = (self.width as usize, width as usize, height as usize);
        Frame {
            width,
            height,
            y: shrink(&self.y, sw, dw, dh),
            u: shrink(&self.u, sw / 2, dw / 2, dh / 2),
            v: shrink(&self.v, sw / 2, dw / 2, dh / 2),
            captured_at: self.captured_at,
        }
    }

    pub(crate) fn planes(&self) -> YuvPlanarImage<'_, u8> {
        YuvPlanarImage {
            y_plane: &self.y,
            y_stride: self.width,
            u_plane: &self.u,
            u_stride: self.width / 2,
            v_plane: &self.v,
            v_stride: self.width / 2,
            width: self.width,
            height: self.height,
        }
    }

    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    fn planes_mut(&mut self) -> YuvPlanarImageMut<'_, u8> {
        let (w, h) = (self.width, self.height);
        YuvPlanarImageMut {
            y_plane: BufferStoreMut::Borrowed(&mut self.y),
            y_stride: w,
            u_plane: BufferStoreMut::Borrowed(&mut self.u),
            u_stride: w / 2,
            v_plane: BufferStoreMut::Borrowed(&mut self.v),
            v_stride: w / 2,
            width: w,
            height: h,
        }
    }
}

/// The caller's off-heap RGBA buffer as a slice, once its size checks out.
pub(crate) fn rgba_target<'a>(
    address: u64,
    capacity: u64,
    width: u32,
    height: u32,
) -> Result<&'a mut [u8], VideoError> {
    let needed = width as u64 * height as u64 * 4;
    if address == 0 || capacity < needed {
        return Err(VideoError::Invalid(format!(
            "RGBA target needs {needed} bytes, got {capacity}"
        )));
    }
    Ok(unsafe { std::slice::from_raw_parts_mut(address as *mut u8, needed as usize) })
}

pub(crate) fn i420_to_rgba(
    image: &YuvPlanarImage<u8>,
    target: &mut [u8],
    range: YuvRange,
    matrix: YuvStandardMatrix,
) -> Result<(), VideoError> {
    yuv420_to_rgba(image, target, image.width * 4, range, matrix)
        .map_err(|e| VideoError::Decoder(e.to_string()))
}
