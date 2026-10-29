//! macOS capture through AVFoundation.
//!
//! AVFoundation does the scaling and pixel format conversion itself: the
//! output is asked for NV12 at the size we encode, whatever the device
//! produces natively (OBS Virtual Camera, for instance, only offers BGRA at
//! its canvas size). Frames arrive on a private dispatch queue and are copied
//! out of the pixel buffer straight away, so AVFoundation's buffer pool never
//! runs dry.

use std::sync::mpsc;
use std::time::Duration;

use block2::RcBlock;
use dispatch2::{DispatchQueue, DispatchRetained};
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, Bool, NSObject, NSObjectProtocol, ProtocolObject};
use objc2::{AnyThread, DefinedClass, define_class, msg_send};
use objc2_av_foundation::{
    AVAuthorizationStatus, AVCaptureConnection, AVCaptureDevice, AVCaptureDeviceDiscoverySession,
    AVCaptureDeviceInput, AVCaptureDevicePosition, AVCaptureDeviceTypeBuiltInWideAngleCamera,
    AVCaptureDeviceTypeExternal, AVCaptureOutput, AVCaptureSession, AVCaptureVideoDataOutput,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVMediaTypeVideo,
};
use objc2_core_foundation::CFString;
use objc2_core_media::{CMSampleBuffer, CMVideoFormatDescriptionGetDimensions};
use objc2_core_video::{
    CVPixelBufferGetBaseAddressOfPlane, CVPixelBufferGetBytesPerRowOfPlane, CVPixelBufferGetHeight,
    CVPixelBufferGetPixelFormatType, CVPixelBufferGetWidth, CVPixelBufferLockBaseAddress,
    CVPixelBufferLockFlags, CVPixelBufferUnlockBaseAddress, kCVPixelBufferHeightKey,
    kCVPixelBufferPixelFormatTypeKey, kCVPixelBufferWidthKey,
    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
};
use objc2_foundation::{NSArray, NSDictionary, NSNumber, NSString};

use super::{CameraInfo, Sink};
use crate::VideoError;
use crate::frame::Frame;

/// Everything that has to stay alive while the camera streams.
pub(crate) struct Session {
    session: Retained<AVCaptureSession>,
    // Never read, only kept: the output holds its delegate weakly.
    _output: Retained<AVCaptureVideoDataOutput>,
    _delegate: Retained<Delegate>,
    _queue: DispatchRetained<DispatchQueue>,
    source: String,
    width: u32,
    height: u32,
}

// AVCaptureSession may be started and stopped from any thread, and nothing
// else here is touched after setup.
unsafe impl Send for Session {}

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

    pub(crate) fn stop(self) {
        unsafe { self.session.stopRunning() };
    }
}

pub(crate) fn permission() -> bool {
    status() == AVAuthorizationStatus::Authorized
}

pub(crate) fn request_permission(timeout: Duration) -> bool {
    if status() != AVAuthorizationStatus::NotDetermined {
        return permission();
    }
    let (tx, rx) = mpsc::channel();
    let handler = RcBlock::new(move |granted: Bool| {
        let _ = tx.send(granted.as_bool());
    });
    unsafe { AVCaptureDevice::requestAccessForMediaType_completionHandler(video(), &handler) };
    rx.recv_timeout(timeout).unwrap_or(false)
}

pub(crate) fn devices() -> Result<Vec<CameraInfo>, VideoError> {
    let devices = unsafe {
        let types = NSArray::from_slice(&[
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
            AVCaptureDeviceTypeExternal,
        ]);
        AVCaptureDeviceDiscoverySession::discoverySessionWithDeviceTypes_mediaType_position(
            &types,
            Some(video()),
            AVCaptureDevicePosition::Unspecified,
        )
        .devices()
    };
    Ok(devices
        .iter()
        .map(|device| unsafe {
            CameraInfo {
                id: device.uniqueID().to_string(),
                name: device.localizedName().to_string(),
                model: device.modelID().to_string(),
            }
        })
        .collect())
}

pub(crate) fn open(
    id: &str,
    target_height: u32,
    _fps: u32,
    sink: Sink,
) -> Result<Session, VideoError> {
    if !permission() {
        return Err(VideoError::Camera(
            "camera access has not been granted".into(),
        ));
    }
    unsafe {
        let device = AVCaptureDevice::deviceWithUniqueID(&NSString::from_str(id))
            .ok_or_else(|| VideoError::Camera(format!("no camera with id {id}")))?;
        let input = AVCaptureDeviceInput::deviceInputWithDevice_error(&device)
            .map_err(|e| VideoError::Camera(e.localizedDescription().to_string()))?;

        let native =
            CMVideoFormatDescriptionGetDimensions(&device.activeFormat().formatDescription());
        let (native_w, native_h) = (native.width as u32, native.height as u32);
        let (width, height) = Sink::output_size(native_w, native_h, target_height);

        let output = AVCaptureVideoDataOutput::new();
        output.setAlwaysDiscardsLateVideoFrames(true);
        output.setVideoSettings(Some(&settings(width, height)));

        let session = AVCaptureSession::new();
        session.beginConfiguration();
        if !session.canAddInput(&input) {
            return Err(VideoError::Camera(
                "the camera cannot be added to a capture session".into(),
            ));
        }
        session.addInput(&input);
        if !session.canAddOutput(&output) {
            return Err(VideoError::Camera(
                "the capture session refused a video output".into(),
            ));
        }
        session.addOutput(&output);
        session.commitConfiguration();

        let queue = DispatchQueue::new("dev.mcnet.video.camera", None);
        let delegate = Delegate::new(sink);
        output.setSampleBufferDelegate_queue(
            Some(ProtocolObject::from_ref(&*delegate)),
            Some(&queue),
        );

        // Blocks until the device is streaming, which is what we want: the
        // constructor either returns a working camera or an error.
        session.startRunning();
        if !session.isRunning() {
            return Err(VideoError::Camera(
                "the capture session did not start".into(),
            ));
        }

        Ok(Session {
            session,
            _output: output,
            _delegate: delegate,
            _queue: queue,
            source: format!("{native_w}x{native_h}"),
            width,
            height,
        })
    }
}

fn status() -> AVAuthorizationStatus {
    unsafe { AVCaptureDevice::authorizationStatusForMediaType(video()) }
}

fn video() -> &'static NSString {
    unsafe { AVMediaTypeVideo }.expect("AVMediaTypeVideo is always defined")
}

/// Asks for video-range NV12 at exactly [width]x[height]. macOS scales in
/// hardware when the device's own mode differs.
fn settings(width: u32, height: u32) -> Retained<NSDictionary<NSString, AnyObject>> {
    // CFString and NSString are toll-free bridged.
    let key = |cf: &'static CFString| unsafe { &*(cf as *const CFString).cast::<NSString>() };
    let format = NSNumber::new_u32(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange);
    let width = NSNumber::new_u32(width);
    let height = NSNumber::new_u32(height);
    unsafe {
        NSDictionary::from_slices(
            &[
                key(kCVPixelBufferPixelFormatTypeKey),
                key(kCVPixelBufferWidthKey),
                key(kCVPixelBufferHeightKey),
            ],
            &[format.as_ref(), width.as_ref(), height.as_ref()],
        )
    }
}

/// Copies an NV12 sample buffer into a [Frame], or returns None if it is in
/// some other format (which would mean AVFoundation ignored the settings).
unsafe fn copy_frame(sample: &CMSampleBuffer) -> Option<Frame> {
    let pixels = unsafe { sample.image_buffer() }?;
    if CVPixelBufferGetPixelFormatType(&pixels) != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange {
        return None;
    }
    unsafe { CVPixelBufferLockBaseAddress(&pixels, CVPixelBufferLockFlags::ReadOnly) };
    let (width, height) = (
        CVPixelBufferGetWidth(&pixels),
        CVPixelBufferGetHeight(&pixels),
    );
    let y_stride = CVPixelBufferGetBytesPerRowOfPlane(&pixels, 0);
    let uv_stride = CVPixelBufferGetBytesPerRowOfPlane(&pixels, 1);
    let frame = unsafe {
        let y = std::slice::from_raw_parts(
            CVPixelBufferGetBaseAddressOfPlane(&pixels, 0) as *const u8,
            y_stride * height,
        );
        let uv = std::slice::from_raw_parts(
            CVPixelBufferGetBaseAddressOfPlane(&pixels, 1) as *const u8,
            uv_stride * height.div_ceil(2),
        );
        Frame::from_nv12(width as u32, height as u32, y, y_stride, uv, uv_stride)
    };
    unsafe { CVPixelBufferUnlockBaseAddress(&pixels, CVPixelBufferLockFlags::ReadOnly) };
    frame.ok()
}

struct Ivars {
    sink: Sink,
}

define_class!(
    // SAFETY: NSObject has no subclassing requirements, and Delegate does
    // not implement Drop.
    #[unsafe(super(NSObject))]
    #[name = "MCNetVideoCaptureDelegate"]
    #[ivars = Ivars]
    struct Delegate;

    unsafe impl NSObjectProtocol for Delegate {}

    unsafe impl AVCaptureVideoDataOutputSampleBufferDelegate for Delegate {
        #[unsafe(method(captureOutput:didOutputSampleBuffer:fromConnection:))]
        fn did_output(
            &self,
            _output: &AVCaptureOutput,
            sample: &CMSampleBuffer,
            _connection: &AVCaptureConnection,
        ) {
            let sink = &self.ivars().sink;
            if !sink.wants_frame() {
                return;
            }
            if let Some(frame) = unsafe { copy_frame(sample) } {
                sink.offer(frame);
            }
        }
    }
);

impl Delegate {
    fn new(sink: Sink) -> Retained<Self> {
        let this = Self::alloc().set_ivars(Ivars { sink });
        unsafe { msg_send![super(this), init] }
    }
}
