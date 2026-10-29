//! Placeholder for platforms without a capture backend. Decoding still works
//! there, so a player can watch others without being able to send.

use std::time::Duration;

use super::{CameraInfo, Sink};
use crate::VideoError;

pub(crate) struct Session;

impl Session {
    pub(crate) fn source(&self) -> String {
        unreachable!()
    }

    pub(crate) fn width(&self) -> u32 {
        unreachable!()
    }

    pub(crate) fn height(&self) -> u32 {
        unreachable!()
    }

    pub(crate) fn stop(self) {}
}

pub(crate) fn permission() -> bool {
    true
}

pub(crate) fn request_permission(_timeout: Duration) -> bool {
    true
}

pub(crate) fn devices() -> Result<Vec<CameraInfo>, VideoError> {
    Err(unsupported())
}

pub(crate) fn open(_id: &str, _height: u32, _fps: u32, _sink: Sink) -> Result<Session, VideoError> {
    Err(unsupported())
}

fn unsupported() -> VideoError {
    VideoError::Camera(format!(
        "camera capture is not supported on {}",
        std::env::consts::OS
    ))
}
