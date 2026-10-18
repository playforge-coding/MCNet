package dev.mcnet.voice

import de.maxhenkel.opus4j.OpusDecoder
import de.maxhenkel.opus4j.OpusEncoder

/**
 * Opus codec settings, in one place so the sender and every receiver agree.
 *
 * 48 kHz mono, 20 ms frames, VOIP tuning. Opus picks the bitrate itself; for
 * speech it lands around 24 to 32 kbps, which is 60 to 80 bytes a frame.
 */
object Opus {
    fun encoder(): OpusEncoder =
        OpusEncoder(SAMPLE_RATE, 1, OpusEncoder.Application.VOIP).apply {
            maxPayloadSize = VoiceFrames.MAX_SIZE - VoiceFrames.HEADER_SIZE
            // Tells the encoder to expect some loss, which makes it spend a
            // few bits on in-band redundancy. A fraction, not a percentage,
            // despite the name.
            maxPacketLossPercentage = 0.1f
        }

    fun decoder(): OpusDecoder =
        OpusDecoder(SAMPLE_RATE, 1).apply {
            // The decoder needs the frame size to synthesize a frame when a
            // packet is missing.
            frameSize = FRAME_SAMPLES
        }
}
