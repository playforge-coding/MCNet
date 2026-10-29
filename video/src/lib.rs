//! Webcam capture, AV1 encode and AV1 decode for MCNet, exposed to Kotlin
//! through UniFFI.
//!
//! Raw frames never cross into the JVM. [`Camera`] hands out opaque
//! [`Frame`] handles, [`Encoder`] turns them into packets, and [`Decoder`]
//! turns packets into [`Picture`]s that write RGBA straight into memory the
//! caller owns. The only bytes that are copied across the boundary are the
//! encoded packets, which are what the network carries anyway.

mod camera;
mod decoder;
mod encoder;
mod frame;
mod sys;
#[cfg(test)]
mod tests;

pub use camera::{Camera, CameraInfo, camera_permission, list_cameras, request_camera_permission};
pub use decoder::{Decoder, Picture};
pub use encoder::{Encoder, EncoderConfig, Packet};
pub use frame::Frame;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum VideoError {
    #[error("camera: {0}")]
    Camera(String),
    #[error("encoder: {0}")]
    Encoder(String),
    #[error("decoder: {0}")]
    Decoder(String),
    #[error("{0}")]
    Invalid(String),
}
