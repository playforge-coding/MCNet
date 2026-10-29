package dev.mcnet.voice

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video framing and the control messages cameras added, byte for byte. */
class VideoProtocolTest {
    @Test
    fun `a group stream header carries the speaker and group number`() {
        val header = VideoStreams.header(0xBEEF, 123_456)
        assertEquals(VideoStreams.HEADER_SIZE, header.size)
        assertEquals(VideoStreams.TYPE_GROUP, header[0])
        assertEquals(0xBEEF to 123_456, VideoStreams.decodeHeader(header))

        assertNull(VideoStreams.decodeHeader(ByteArray(VideoStreams.HEADER_SIZE)), "unknown stream types are refused")
        assertNull(VideoStreams.decodeHeader(header.copyOf(3)))
    }

    @Test
    fun `a frame header carries the length and the keyframe flag`() {
        assertEquals(70_000 to true, VideoStreams.decodeFrameHeader(VideoStreams.frameHeader(true, 70_000)))
        assertEquals(12 to false, VideoStreams.decodeFrameHeader(VideoStreams.frameHeader(false, 12)))
        assertContentEquals(byteArrayOf(0, 0, 0, 5, 1), VideoStreams.frameHeader(true, 5))
        assertNull(
            VideoStreams.decodeFrameHeader(VideoStreams.frameHeader(false, VideoStreams.MAX_FRAME + 1)),
            "a length past the guard is treated as garbage",
        )
    }

    private fun sequenced(block: (VideoStreams.Sequencer) -> Unit): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val sequencer =
            VideoStreams.Sequencer { _, group, event ->
                out +=
                    group to
                    when (event) {
                        VideoEvent.Begin -> "begin"
                        VideoEvent.End -> "end"
                        is VideoEvent.Frame -> "frame ${event.data[0]}"
                    }
            }
        block(sequencer)
        return out
    }

    private fun frame(n: Int) = VideoEvent.Frame(n == 0, byteArrayOf(n.toByte()))

    @Test
    fun `a newer group waits for the one before it to end`() {
        val out =
            sequenced { s ->
                s.accept(0, 1, VideoEvent.Begin)
                s.accept(0, 1, frame(0))
                // Group 2's stream overtakes the tail of group 1.
                s.accept(0, 2, VideoEvent.Begin)
                s.accept(0, 2, frame(0))
                s.accept(0, 1, frame(1))
                s.accept(0, 1, VideoEvent.End)
                s.accept(0, 2, frame(1))
            }
        assertEquals(
            listOf(
                1 to "begin",
                1 to "frame 0",
                1 to "frame 1",
                1 to "end",
                2 to "begin",
                2 to "frame 0",
                2 to "frame 1",
            ),
            out,
        )
    }

    @Test
    fun `a stalled group is given up rather than holding the next one forever`() {
        val out =
            sequenced { s ->
                s.accept(0, 1, VideoEvent.Begin)
                s.accept(0, 2, VideoEvent.Begin)
                repeat(31) { s.accept(0, 2, frame(it)) }
                // Group 1 limps in after being given up; it must be ignored.
                s.accept(0, 1, frame(5))
            }
        assertEquals(1 to "begin", out[0])
        assertEquals(1 to "end", out[1], "the stalled group gets an End of its own")
        assertEquals(2 to "begin", out[2])
        assertEquals(31, out.count { it.first == 2 && it.second.startsWith("frame") })
        assertTrue(out.none { it == 1 to "frame 5" })
    }

    @Test
    fun `a group two ahead replaces both the current and the held one`() {
        val out =
            sequenced { s ->
                s.accept(0, 1, VideoEvent.Begin)
                s.accept(0, 2, VideoEvent.Begin)
                s.accept(0, 3, VideoEvent.Begin)
                s.accept(0, 2, frame(0))
                s.accept(0, 3, frame(0))
            }
        assertEquals(listOf(1 to "begin", 1 to "end", 3 to "begin", 3 to "frame 0"), out)
    }

    @Test
    fun `the roster carries each participant's camera flag`() {
        val roster =
            listOf(
                RosterEntry(1, UUID.randomUUID(), "alice", camera = true),
                RosterEntry(2, UUID.randomUUID(), "bob"),
            )
        assertEquals(roster, Control.decodeRoster(Control.encodeRoster(roster)))
    }

    @Test
    fun `camera and watch messages round-trip`() {
        assertEquals(true, Control.decodeCamera(Control.encodeCamera(true)))
        assertEquals(false, Control.decodeCamera(Control.encodeCamera(false)))
        assertNull(Control.decodeCamera(ByteArray(0)))

        assertEquals(65_535 to true, Control.decodeWatch(Control.encodeWatch(65_535, true)))
        assertEquals(7 to false, Control.decodeWatch(Control.encodeWatch(7, false)))
        assertNull(Control.decodeWatch(ByteArray(2)))
        assertNotNull(Control.decodeWatch(byteArrayOf(0, 1, 1)))
    }
}
