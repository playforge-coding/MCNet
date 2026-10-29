//! Webcam capture. The [`Camera`] front end is the same everywhere; each OS
//! gets a thin backend over its own capture API in `camera/`, because no
//! cross-platform capture crate is maintained well enough to depend on.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::VideoError;
use crate::frame::Frame;

#[cfg(target_os = "macos")]
#[path = "camera/avfoundation.rs"]
mod backend;

#[cfg(windows)]
#[path = "camera/mediafoundation.rs"]
mod backend;

#[cfg(target_os = "linux")]
#[path = "camera/v4l2.rs"]
mod backend;

#[cfg(not(any(target_os = "macos", windows, target_os = "linux")))]
#[path = "camera/unsupported.rs"]
mod backend;

#[derive(uniffi::Record)]
pub struct CameraInfo {
    /// Stable across reboots and replugging, unlike an index.
    pub id: String,
    pub name: String,
    pub model: String,
}

/// True if the OS will let this process open a camera. Only macOS asks;
/// elsewhere this is always true.
#[uniffi::export]
pub fn camera_permission() -> bool {
    backend::permission()
}

/// Asks the OS for camera access and waits for the answer. On macOS this is
/// the system prompt, attributed to whichever app launched the JVM, and that
/// app's Info.plist must carry `NSCameraUsageDescription` or the process is
/// killed instead of prompted.
#[uniffi::export]
pub fn request_camera_permission(timeout_ms: u64) -> bool {
    backend::request_permission(Duration::from_millis(timeout_ms))
}

#[uniffi::export]
pub fn list_cameras() -> Result<Vec<CameraInfo>, VideoError> {
    backend::devices()
}

/// An open camera, delivering I420 frames at (roughly) the requested height,
/// with the width following the camera's own aspect ratio.
///
/// The backend pushes frames from an OS thread into a two-slot channel. When
/// the consumer falls behind, new frames are dropped rather than queued,
/// because a stale frame is worth nothing to a live call.
#[derive(uniffi::Object)]
pub struct Camera {
    frames: Mutex<Receiver<Frame>>,
    dropped: Arc<AtomicU64>,
    session: Mutex<Option<backend::Session>>,
    source: String,
    width: u32,
    height: u32,
}

#[uniffi::export]
impl Camera {
    /// Opens the camera with [id] (from [list_cameras]) and starts streaming.
    #[uniffi::constructor]
    pub fn open(id: String, height: u32, fps: u32) -> Result<Arc<Self>, VideoError> {
        if height < 16 || fps == 0 {
            return Err(VideoError::Invalid(format!(
                "cannot capture {height}p at {fps} fps"
            )));
        }
        let (tx, rx) = mpsc::sync_channel(2);
        let dropped = Arc::new(AtomicU64::new(0));
        let sink = Sink {
            frames: tx,
            dropped: dropped.clone(),
            target_height: height,
            interval: Duration::from_secs(1) / fps,
            last: Mutex::new(None),
        };
        let session = backend::open(&id, height, fps, sink)?;
        Ok(Arc::new(Camera {
            frames: Mutex::new(rx),
            dropped,
            source: session.source(),
            width: session.width(),
            height: session.height(),
            session: Mutex::new(Some(session)),
        }))
    }

    /// Blocks until the next frame, or returns null after [timeout_ms].
    pub fn capture(&self, timeout_ms: u64) -> Result<Option<Arc<Frame>>, VideoError> {
        match self
            .frames
            .lock()
            .unwrap()
            .recv_timeout(Duration::from_millis(timeout_ms))
        {
            Ok(frame) => Ok(Some(Arc::new(frame))),
            Err(RecvTimeoutError::Timeout) => Ok(None),
            Err(RecvTimeoutError::Disconnected) => Err(VideoError::Camera("camera stopped".into())),
        }
    }

    /// What the camera is producing before scaling, for logs.
    pub fn source(&self) -> String {
        self.source.clone()
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    /// Frames thrown away because nobody called [capture] in time.
    pub fn dropped(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }

    /// Stops streaming. Not called `close`, which the generated Kotlin class
    /// already uses for releasing the handle itself.
    pub fn stop(&self) {
        if let Some(session) = self.session.lock().unwrap().take() {
            session.stop();
        }
    }
}

impl Drop for Camera {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Where a backend delivers converted frames.
pub(crate) struct Sink {
    frames: SyncSender<Frame>,
    dropped: Arc<AtomicU64>,
    target_height: u32,
    interval: Duration,
    last: Mutex<Option<Instant>>,
}

impl Sink {
    /// The output size for a camera producing [width]x[height] when [target]
    /// lines are wanted: never upscaled, aspect kept, both sides even.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pub(crate) fn output_size(width: u32, height: u32, target: u32) -> (u32, u32) {
        let out_h = target.min(height) & !1;
        let out_w =
            ((width as u64 * out_h as u64 + height as u64 / 2) / height.max(1) as u64) as u32 & !1;
        (out_w, out_h)
    }

    /// The whole-number factor [Frame::downscale] should use to bring
    /// [height] lines down towards the target, for backends whose capture
    /// API cannot scale.
    pub(crate) fn downscale_factor(height: u32, target: u32) -> u32 {
        (height / target.max(1)).max(1)
    }

    /// Cheap enough to call for every frame the driver produces. Frames that
    /// arrive faster than the requested rate are skipped here, so a 60 fps
    /// camera feeds a 30 fps encoder every other frame.
    pub(crate) fn wants_frame(&self) -> bool {
        let now = Instant::now();
        let mut last = self.last.lock().unwrap();
        // Some slack, so driver jitter around the exact rate does not drop
        // every other frame.
        if last.is_some_and(|at| now.duration_since(at) < self.interval * 4 / 5) {
            return false;
        }
        *last = Some(now);
        true
    }

    /// Hands a frame on. The frame is downscaled by a whole-number factor
    /// first if the backend could not get it to the target size itself.
    pub(crate) fn offer(&self, frame: Frame) {
        let factor = Self::downscale_factor(frame.height, self.target_height);
        if let Err(TrySendError::Full(_)) = self.frames.try_send(frame.downscale(factor)) {
            self.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}
