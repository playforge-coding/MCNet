use std::os::raw::c_int;
use std::ptr;
use std::sync::{Arc, Mutex};

use crate::VideoError;
use crate::frame::Frame;
use crate::sys::*;

#[derive(uniffi::Record, Clone, Debug)]
pub struct EncoderConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// Constant-bitrate target. The rate controller holds the average here
    /// and lets single frames (keyframes above all) overshoot.
    pub bitrate_kbps: u32,
    /// Frames between keyframes. Each keyframe starts a new group of
    /// pictures, and a group is the unit the transport sends on one stream.
    pub keyframe_interval: u32,
    /// libaom's realtime speed preset. Higher is faster and blurrier.
    pub speed: u32,
    pub threads: u32,
}

#[derive(uniffi::Record)]
pub struct Packet {
    pub data: Vec<u8>,
    pub keyframe: bool,
    /// Presentation time, in frames since the encoder started.
    pub pts: i64,
}

/// A libaom AV1 encoder in realtime mode: no lookahead, so every frame comes
/// out of [encode] before the next goes in.
#[derive(uniffi::Object)]
pub struct Encoder {
    inner: Mutex<Inner>,
}

struct Inner {
    // Boxed so its address never changes; libaom keeps internal pointers
    // that are only valid for the context they were created in.
    ctx: Box<aom_codec_ctx_t>,
    cfg: aom_codec_enc_cfg_t,
    pts: i64,
}

// The context is only ever touched while holding the mutex.
unsafe impl Send for Inner {}

#[uniffi::export]
impl Encoder {
    #[uniffi::constructor]
    pub fn new(config: EncoderConfig) -> Result<Arc<Self>, VideoError> {
        if !config.width.is_multiple_of(2) || !config.height.is_multiple_of(2) || config.fps == 0 {
            return Err(VideoError::Invalid(format!(
                "unusable encoder config {config:?}"
            )));
        }
        unsafe {
            let iface = aom_codec_av1_cx();
            let mut cfg = aom_codec_enc_cfg_t::default();
            check(aom_codec_enc_config_default(
                iface,
                &mut cfg,
                AOM_USAGE_REALTIME,
            ))?;

            cfg.g_w = config.width;
            cfg.g_h = config.height;
            cfg.g_timebase = aom_rational {
                num: 1,
                den: config.fps as c_int,
            };
            cfg.g_threads = config.threads.max(1);
            cfg.g_lag_in_frames = 0;
            cfg.g_error_resilient = 0;
            cfg.rc_end_usage = AOM_CBR;
            cfg.rc_target_bitrate = config.bitrate_kbps;
            // The buffer model and quantizer range libwebrtc uses for AV1.
            cfg.rc_min_quantizer = 10;
            cfg.rc_max_quantizer = 56;
            cfg.rc_undershoot_pct = 50;
            cfg.rc_overshoot_pct = 50;
            cfg.rc_buf_initial_sz = 600;
            cfg.rc_buf_optimal_sz = 600;
            cfg.rc_buf_sz = 1000;
            cfg.kf_mode = AOM_KF_AUTO;
            cfg.kf_min_dist = config.keyframe_interval;
            cfg.kf_max_dist = config.keyframe_interval;

            let mut ctx = Box::new(aom_codec_ctx_t::default());
            check(aom_codec_enc_init_ver(
                &mut *ctx,
                iface,
                &cfg,
                0,
                AOM_ENCODER_ABI_VERSION as c_int,
            ))?;

            let mut inner = Inner { ctx, cfg, pts: 0 };
            let tile_columns = match config.threads {
                0..=1 => 0,
                2..=3 => 1,
                _ => 2,
            };
            for (id, value) in [
                (AOME_SET_CPUUSED, config.speed as c_int),
                (AV1E_SET_ROW_MT, 1),
                (AV1E_SET_TILE_COLUMNS, tile_columns),
                (AV1E_SET_AQ_MODE, 3),
                (AV1E_SET_ENABLE_CDEF, 1),
                (AV1E_SET_ENABLE_TPL_MODEL, 0),
                (AV1E_SET_DELTAQ_MODE, 0),
                (AV1E_SET_ENABLE_ORDER_HINT, 0),
                (AV1E_SET_ENABLE_GLOBAL_MOTION, 0),
                (AV1E_SET_ENABLE_WARPED_MOTION, 0),
                (AV1E_SET_ENABLE_OBMC, 0),
                (AV1E_SET_ENABLE_PALETTE, 0),
                (AV1E_SET_COEFF_COST_UPD_FREQ, 3),
                (AV1E_SET_MODE_COST_UPD_FREQ, 3),
                (AV1E_SET_MV_COST_UPD_FREQ, 3),
                (AOME_SET_MAX_INTRA_BITRATE_PCT, 300),
                // Tag the stream so the decoder converts to RGB with the
                // same matrix the frames were built with.
                (AV1E_SET_COLOR_PRIMARIES, AOM_CICP_CP_BT_709 as c_int),
                (
                    AV1E_SET_TRANSFER_CHARACTERISTICS,
                    AOM_CICP_TC_BT_709 as c_int,
                ),
                (AV1E_SET_MATRIX_COEFFICIENTS, AOM_CICP_MC_BT_709 as c_int),
                (AV1E_SET_COLOR_RANGE, 0),
            ] {
                check(aom_codec_control(&mut *inner.ctx, id as c_int, value))
                    .map_err(|e| VideoError::Encoder(format!("control {id}: {e}")))?;
            }

            Ok(Arc::new(Encoder {
                inner: Mutex::new(inner),
            }))
        }
    }

    /// Encodes one frame. Usually yields exactly one packet; yields none when
    /// the rate controller decides to drop the frame to stay on budget.
    pub fn encode(
        &self,
        frame: Arc<Frame>,
        force_keyframe: bool,
    ) -> Result<Vec<Packet>, VideoError> {
        let mut inner = self.inner.lock().unwrap();
        if frame.width != inner.cfg.g_w || frame.height != inner.cfg.g_h {
            return Err(VideoError::Invalid(format!(
                "frame is {}x{} but the encoder is {}x{}",
                frame.width, frame.height, inner.cfg.g_w, inner.cfg.g_h
            )));
        }
        unsafe {
            let mut image = aom_image_t::default();
            if aom_img_wrap(
                &mut image,
                AOM_IMG_FMT_I420,
                frame.width,
                frame.height,
                1,
                ptr::null_mut(),
            )
            .is_null()
            {
                return Err(VideoError::Encoder(
                    "could not describe the frame to libaom".into(),
                ));
            }
            // The planes live in three separate allocations, so point at each
            // one rather than letting aom_img_wrap assume a single buffer.
            image.planes[0] = frame.y.as_ptr() as *mut u8;
            image.planes[1] = frame.u.as_ptr() as *mut u8;
            image.planes[2] = frame.v.as_ptr() as *mut u8;
            image.stride[0] = frame.width as c_int;
            image.stride[1] = (frame.width / 2) as c_int;
            image.stride[2] = (frame.width / 2) as c_int;

            let pts = inner.pts;
            inner.pts += 1;
            let flags = if force_keyframe {
                AOM_EFLAG_FORCE_KF as aom_enc_frame_flags_t
            } else {
                0
            };
            check(aom_codec_encode(&mut *inner.ctx, &image, pts, 1, flags))?;
            Ok(inner.drain(pts))
        }
    }

    /// Retargets the rate controller without restarting the stream. This is
    /// the knob congestion feedback turns.
    pub fn set_bitrate(&self, kbps: u32) -> Result<(), VideoError> {
        let mut inner = self.inner.lock().unwrap();
        inner.cfg.rc_target_bitrate = kbps;
        let cfg = inner.cfg;
        unsafe { check(aom_codec_enc_config_set(&mut *inner.ctx, &cfg)) }
    }
}

impl Inner {
    unsafe fn drain(&mut self, pts: i64) -> Vec<Packet> {
        let mut packets = Vec::new();
        let mut iter: aom_codec_iter_t = ptr::null();
        loop {
            let packet = unsafe { aom_codec_get_cx_data(&mut *self.ctx, &mut iter) };
            if packet.is_null() {
                break;
            }
            let packet = unsafe { &*packet };
            if packet.kind != aom_codec_cx_pkt_kind::AOM_CODEC_CX_FRAME_PKT {
                continue;
            }
            let frame = unsafe { packet.data.frame };
            let data = unsafe { std::slice::from_raw_parts(frame.buf as *const u8, frame.sz) };
            packets.push(Packet {
                data: data.to_vec(),
                keyframe: frame.flags & AOM_FRAME_IS_KEY != 0,
                pts,
            });
        }
        packets
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        unsafe {
            aom_codec_destroy(&mut *self.ctx);
        }
    }
}

fn check(result: aom_codec_err_t) -> Result<(), VideoError> {
    if result == aom_codec_err_t::AOM_CODEC_OK {
        return Ok(());
    }
    let message = unsafe { std::ffi::CStr::from_ptr(aom_codec_err_to_string(result)) };
    Err(VideoError::Encoder(message.to_string_lossy().into_owned()))
}
