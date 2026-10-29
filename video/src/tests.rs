use yuv::{YuvRange, YuvStandardMatrix};

use crate::camera::Sink;
use crate::frame::i420_to_rgba;
use crate::{Decoder, Encoder, EncoderConfig, Frame};

fn config() -> EncoderConfig {
    EncoderConfig {
        width: 640,
        height: 360,
        fps: 30,
        bitrate_kbps: 500,
        keyframe_interval: 60,
        speed: 10,
        threads: 2,
    }
}

/// What the source frame looks like in RGBA, for comparing against the decoder.
fn reference_rgba(frame: &Frame) -> Vec<u8> {
    let mut out = vec![0u8; (frame.width * frame.height * 4) as usize];
    i420_to_rgba(
        &frame.planes(),
        &mut out,
        YuvRange::Limited,
        YuvStandardMatrix::Bt709,
    )
    .unwrap();
    out
}

fn psnr(a: &[u8], b: &[u8]) -> f64 {
    let mse = a
        .iter()
        .zip(b)
        .map(|(&x, &y)| (x as f64 - y as f64).powi(2))
        .sum::<f64>()
        / a.len() as f64;
    10.0 * (255.0 * 255.0 / mse.max(1e-9)).log10()
}

#[test]
fn round_trip_keeps_the_picture() {
    let encoder = Encoder::new(config()).unwrap();
    let decoder = Decoder::new(2).unwrap();

    let mut keyframes = Vec::new();
    let mut last = None;
    for index in 0..90 {
        let frame = Frame::test_pattern(640, 360, index).unwrap();
        let packets = encoder.encode(frame.clone(), false).unwrap();
        for packet in packets {
            if packet.keyframe {
                keyframes.push(packet.pts);
            }
            let picture = decoder
                .decode(packet.data)
                .unwrap()
                .expect("one picture per packet");
            assert_eq!((picture.width(), picture.height()), (640, 360));
            last = Some((frame.clone(), picture));
        }
    }

    assert_eq!(
        keyframes,
        vec![0, 60],
        "a keyframe opens the stream and then every interval"
    );
    let (frame, picture) = last.unwrap();
    let quality = psnr(&reference_rgba(&frame), &picture.rgba().unwrap());
    assert!(
        quality > 30.0,
        "decoded picture should resemble the source; PSNR {quality:.1} dB"
    );
}

#[test]
fn forced_keyframe_starts_a_new_group() {
    let encoder = Encoder::new(config()).unwrap();
    let mut flags = Vec::new();
    for index in 0..10 {
        let frame = Frame::test_pattern(640, 360, index).unwrap();
        for packet in encoder.encode(frame, index == 5).unwrap() {
            flags.push(packet.keyframe);
        }
    }
    assert_eq!(flags.iter().filter(|&&k| k).count(), 2);
    assert!(flags[5]);
}

#[test]
fn bitrate_can_change_mid_stream() {
    let encoder = Encoder::new(config()).unwrap();
    encoder
        .encode(Frame::test_pattern(640, 360, 0).unwrap(), false)
        .unwrap();
    encoder.set_bitrate(200).unwrap();
    assert!(
        !encoder
            .encode(Frame::test_pattern(640, 360, 1).unwrap(), false)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn wrong_frame_size_is_refused() {
    let encoder = Encoder::new(config()).unwrap();
    assert!(
        encoder
            .encode(Frame::test_pattern(320, 180, 0).unwrap(), false)
            .is_err()
    );
}

#[test]
fn a_frame_previews_as_opaque_rgba() {
    let frame = Frame::test_pattern(64, 32, 0).unwrap();
    let mut out = vec![0u8; 64 * 32 * 4];
    frame
        .write_rgba(out.as_mut_ptr() as u64, out.len() as u64)
        .unwrap();
    assert!(out.iter().skip(3).step_by(4).all(|&a| a == 255));
    assert_eq!(out, reference_rgba(&frame));
    assert!(frame.write_rgba(out.as_mut_ptr() as u64, 16).is_err());
}

#[test]
fn nv12_deinterleaves_chroma_and_skips_padding() {
    // 16x16 with each row padded to 32 bytes, as capture drivers do.
    let (w, h, stride) = (16usize, 16usize, 32usize);
    let mut y = vec![0u8; stride * h];
    let mut uv = vec![0u8; stride * h / 2];
    for row in 0..h {
        y[row * stride..row * stride + w].fill(50);
    }
    for row in 0..h / 2 {
        for col in 0..w / 2 {
            uv[row * stride + col * 2] = 90;
            uv[row * stride + col * 2 + 1] = 160;
        }
    }
    let frame = Frame::from_nv12(16, 16, &y, stride, &uv, stride).unwrap();
    assert!(frame.y.iter().all(|&p| p == 50));
    assert!(frame.u.iter().all(|&p| p == 90));
    assert!(frame.v.iter().all(|&p| p == 160));
}

#[test]
fn nv12_refuses_short_planes() {
    assert!(Frame::from_nv12(16, 16, &[0; 100], 16, &[0; 128], 16).is_err());
}

#[test]
fn yuyv_unpacks_into_planes() {
    // Four pixels per row as Y0 U Y1 V pairs, rows padded to 40 bytes.
    let row = [10u8, 100, 20, 200, 30, 110, 40, 210];
    let mut data = Vec::new();
    for _ in 0..16 {
        let mut line = row.repeat(4);
        line.resize(40, 0);
        data.extend_from_slice(&line);
    }
    let frame = Frame::from_yuyv(16, 16, &data, 40).unwrap();
    assert_eq!(&frame.y[..4], &[10, 20, 30, 40]);
    assert_eq!(&frame.u[..2], &[100, 110]);
    assert_eq!(&frame.v[..2], &[200, 210]);
    assert!(Frame::from_yuyv(16, 16, &data[..100], 40).is_err());
}

#[test]
fn downscale_averages_blocks() {
    let mut frame = Frame::blank(64, 64).unwrap();
    for (i, p) in frame.y.iter_mut().enumerate() {
        *p = if (i / 64) % 2 == 0 { 0 } else { 200 };
    }
    let small = frame.downscale(2);
    assert_eq!((small.width, small.height), (32, 32));
    assert!(small.y.iter().all(|&p| p == 100));
    assert_eq!(small.u.len(), 16 * 16);
}

#[test]
fn sizes_keep_aspect_and_never_upscale() {
    assert_eq!(Sink::output_size(1920, 1080, 360), (640, 360));
    assert_eq!(Sink::output_size(1280, 960, 360), (480, 360));
    assert_eq!(Sink::output_size(320, 240, 360), (320, 240));
    assert_eq!(Sink::downscale_factor(1080, 360), 3);
    assert_eq!(Sink::downscale_factor(480, 360), 1);
    assert_eq!(Sink::downscale_factor(720, 360), 2);
}
