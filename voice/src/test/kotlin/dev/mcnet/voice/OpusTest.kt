package dev.mcnet.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the Opus native loads on this platform and that a frame survives
 * the codec, which is the one thing about the audio path a build machine can
 * check.
 */
class OpusTest {
    @Test
    fun `a speech-sized frame encodes small and decodes to the same length`() {
        val encoder = Opus.encoder()
        val decoder = Opus.decoder()
        try {
            val tone = ShortArray(FRAME_SAMPLES) { (sin(2 * PI * 440 * it / SAMPLE_RATE) * 8000).toInt().toShort() }

            val packet = encoder.encode(tone)
            println("opus ${encoder.opusVersion}: 20 ms frame encoded to ${packet.size} bytes")
            assertTrue(packet.isNotEmpty())
            assertTrue(
                packet.size + VoiceFrames.HEADER_SIZE <= VoiceFrames.MAX_SIZE,
                "a frame must fit a datagram with the header on; got ${packet.size}",
            )

            val pcm = decoder.decode(packet)
            assertEquals(FRAME_SAMPLES, pcm.size, "decoder should produce one 20 ms frame")

            // Packet loss concealment must also produce a full frame, since
            // playback queues whatever the decoder returns.
            assertEquals(FRAME_SAMPLES, decoder.decode(null).size)
        } finally {
            encoder.close()
            decoder.close()
        }
    }
}
