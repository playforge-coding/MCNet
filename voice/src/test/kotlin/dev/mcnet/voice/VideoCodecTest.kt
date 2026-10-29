package dev.mcnet.voice

import dev.mcnet.video.ffi.Decoder
import dev.mcnet.video.ffi.Encoder
import dev.mcnet.video.ffi.EncoderConfig
import dev.mcnet.video.ffi.Frame
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the native video library loads from the classpath through JNA on
 * the build machine, that the committed bindings match it, and that a frame
 * survives the trip through both codecs. The codec behavior itself is
 * covered by the Rust tests in video/.
 */
class VideoCodecTest {
    @Test
    fun `frames round trip through the bindings into off-heap RGBA`() {
        val config =
            EncoderConfig(
                width = 320u,
                height = 180u,
                fps = 30u,
                bitrateKbps = 300u,
                keyframeInterval = 30u,
                speed = 10u,
                threads = 1u,
            )
        Encoder(config).use { encoder ->
            Decoder(1u).use { decoder ->
                Arena.ofConfined().use { arena ->
                    val rgba = arena.allocate(320L * 180 * 4)
                    var pictures = 0
                    for (index in 0 until 10) {
                        val packets = Frame.testPattern(320u, 180u, index.toUInt()).use { encoder.encode(it, false) }
                        if (index == 0) assertTrue(packets.single().keyframe, "the stream must open on a keyframe")
                        for (packet in packets) {
                            decoder.decode(packet.data)?.use { picture ->
                                assertEquals(320u, picture.width())
                                picture.writeRgba(rgba.address().toULong(), rgba.byteSize().toULong())
                                pictures++
                            }
                        }
                    }
                    assertEquals(10, pictures)
                    // Every pixel opaque: the decoder wrote the whole buffer.
                    for (offset in 3L until rgba.byteSize() step 4) {
                        assertEquals(-1, rgba.get(ValueLayout.JAVA_BYTE, offset).toInt())
                    }
                }
            }
        }
    }
}
