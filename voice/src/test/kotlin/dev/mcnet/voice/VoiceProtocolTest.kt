package dev.mcnet.voice

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The wire format both sides must agree on. Everything here is bytes in,
 * bytes out, so it is checked exactly.
 */
class VoiceProtocolTest {
    @Test
    fun `an audio frame round-trips and the host can restamp the speaker`() {
        val payload = ByteArray(83) { (it * 7).toByte() }
        val bytes = VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, 123_456, payload)
        assertEquals(VoiceFrames.HEADER_SIZE + payload.size, bytes.size)

        VoiceFrames.restamp(bytes, 0xBEEF)
        assertEquals(0xBEEF, VoiceFrames.speakerOf(bytes))

        val frame = assertNotNull(VoiceFrames.decode(bytes))
        assertEquals(VoiceFrames.TYPE_AUDIO, frame.type)
        assertEquals(0xBEEF, frame.speaker)
        assertEquals(123_456, frame.sequence)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `an end marker has no payload`() {
        val frame = assertNotNull(VoiceFrames.decode(VoiceFrames.encode(VoiceFrames.TYPE_END, 0, 9, ByteArray(0))))
        assertEquals(VoiceFrames.TYPE_END, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `garbage is rejected before it reaches the relay`() {
        assertNull(VoiceFrames.decode(ByteArray(3)))
        assertNull(VoiceFrames.decode(VoiceFrames.encode(99, 0, 0, ByteArray(1))))
        assertNull(
            VoiceFrames.decode(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, 0, ByteArray(VoiceFrames.MAX_SIZE))),
        )
        assertEquals(false, VoiceFrames.isWellFormed(byteArrayOf(VoiceFrames.TYPE_AUDIO)))
    }

    @Test
    fun `a roster survives names that are not ascii`() {
        val roster =
            listOf(
                RosterEntry(1, UUID.randomUUID(), "alice"),
                RosterEntry(65_535, UUID.randomUUID(), "Zoë Ωmega"),
                RosterEntry(7, UUID(0, 0), ""),
            )
        assertEquals(roster, Control.decodeRoster(Control.encodeRoster(roster)))
    }

    @Test
    fun `settings carry the range and refuse one that cannot be right`() {
        assertEquals(48f, Control.decodeSettings(Control.encodeSettings(48f)))
        assertEquals(0f, Control.decodeSettings(Control.encodeSettings(0f)))
        assertNull(Control.decodeSettings(Control.encodeSettings(-1f)))
        assertNull(Control.decodeSettings(Control.encodeSettings(Float.NaN)))
        assertNull(Control.decodeSettings(Control.encodeSettings(Float.POSITIVE_INFINITY)))
        assertNull(Control.decodeSettings(byteArrayOf(1, 2)))
    }

    @Test
    fun `a control message is length-prefixed with the type inside the length`() {
        val bytes = Control.frame(Control.ROSTER, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(0, 4, Control.ROSTER, 1, 2, 3), bytes)
        assertContentEquals(byteArrayOf(0, 1, Control.PING), Control.frame(Control.PING))
    }
}
