package dev.mcnet.voice

import computer.iroh.RecvStream
import computer.iroh.SendStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The ALPN voice connections arrive on. Registered with the core's protocol
 * registry, so the host's accept loop routes these to [VoiceServer] and never
 * to the Minecraft server.
 */
val VOICE_ALPN: ByteArray = "mcnet-voice/1".toByteArray(Charsets.US_ASCII)

/** Opus runs at 48 kHz; a 20 ms frame is 960 samples. */
const val SAMPLE_RATE = 48_000
const val FRAME_SAMPLES = 960
const val FRAME_MILLIS = 20

/**
 * Audio travels in QUIC datagrams: unreliable, unordered, encrypted. A frame
 * that arrives late is worthless, so retransmission would only add delay.
 *
 * Layout, big-endian:
 *
 * ```
 * u8 type | u16 speaker | u32 sequence | opus payload
 * ```
 *
 * A client sends with speaker 0 and the host stamps the sender's id before
 * fanning out, which is why [restamp] writes in place.
 */
object VoiceFrames {
    const val TYPE_AUDIO: Byte = 1

    /**
     * Marks the end of a talk burst. Carries no payload. Lets a receiver drain
     * its jitter buffer right away instead of waiting out a timeout.
     */
    const val TYPE_END: Byte = 2

    const val HEADER_SIZE = 7

    /**
     * Anything larger is dropped before it is relayed. Well under the 1162
     * byte datagram floor the core's `DatagramProbeTest` measured, and far
     * above the 60 to 120 bytes a 20 ms Opus frame actually takes.
     */
    const val MAX_SIZE = 1000

    class Frame(
        val type: Byte,
        val speaker: Int,
        val sequence: Int,
        val payload: ByteArray,
    )

    fun encode(
        type: Byte,
        speaker: Int,
        sequence: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        out.put(type)
        out.putShort(speaker.toShort())
        out.putInt(sequence)
        out.put(payload)
        return out.array()
    }

    /** Null when the bytes are not a frame this version understands. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE || bytes.size > MAX_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes)
        val type = buffer.get()
        if (type != TYPE_AUDIO && type != TYPE_END) return null
        val speaker = buffer.getShort().toInt() and 0xFFFF
        val sequence = buffer.getInt()
        val payload = ByteArray(bytes.size - HEADER_SIZE).also { buffer.get(it) }
        return Frame(type, speaker, sequence, payload)
    }

    /** True if [bytes] has a valid header. Cheaper than [decode] on the relay path. */
    fun isWellFormed(bytes: ByteArray): Boolean =
        bytes.size in HEADER_SIZE..MAX_SIZE && (bytes[0] == TYPE_AUDIO || bytes[0] == TYPE_END)

    fun restamp(
        bytes: ByteArray,
        speaker: Int,
    ) {
        bytes[1] = (speaker shr 8).toByte()
        bytes[2] = speaker.toByte()
    }

    fun speakerOf(bytes: ByteArray): Int = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
}

/** One participant as the host announces it. */
data class RosterEntry(
    val speaker: Int,
    val uuid: UUID,
    val name: String,
)

/**
 * Control messages ride a single bidirectional QUIC stream per connection,
 * each prefixed with a big-endian u16 length and a type byte. Everything that
 * must arrive, in order, goes here; audio does not.
 */
object Control {
    /** Host to client: the full participant list. Sent on every change. */
    const val ROSTER: Byte = 0x10

    /** Either direction. Keeps the connection from idling out while nobody talks. */
    const val PING: Byte = 0x20
    const val PONG: Byte = 0x21

    const val MAX_MESSAGE = 64 * 1024

    class Message(
        val type: Byte,
        val body: ByteArray,
    )

    fun frame(
        type: Byte,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        require(body.size + 1 <= MAX_MESSAGE) { "control message too large" }
        val out = ByteBuffer.allocate(2 + 1 + body.size)
        out.putShort((body.size + 1).toShort())
        out.put(type)
        out.put(body)
        return out.array()
    }

    suspend fun write(
        send: SendStream,
        type: Byte,
        body: ByteArray = ByteArray(0),
    ) = send.writeAll(frame(type, body))

    /** Null at a clean end of stream. Throws if the peer vanished. */
    suspend fun read(recv: RecvStream): Message? {
        val header = readExactly(recv, 2) ?: return null
        val length = ByteBuffer.wrap(header).getShort().toInt() and 0xFFFF
        if (length < 1) return null
        val rest = readExactly(recv, length) ?: return null
        return Message(rest[0], rest.copyOfRange(1, rest.size))
    }

    /**
     * The bindings' `read` may return fewer bytes than asked, and an empty
     * array means the stream finished.
     */
    private suspend fun readExactly(
        recv: RecvStream,
        count: Int,
    ): ByteArray? {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val chunk = recv.read((count - filled).toUInt())
            if (chunk.isEmpty()) return null
            chunk.copyInto(out, filled)
            filled += chunk.size
        }
        return out
    }

    fun encodeRoster(entries: List<RosterEntry>): ByteArray {
        val names =
            entries.map {
                it.name
                    .toByteArray(Charsets.UTF_8)
                    .take(255)
                    .toByteArray()
            }
        val size = 2 + entries.indices.sumOf { 2 + 16 + 1 + names[it].size }
        val out = ByteBuffer.allocate(size)
        out.putShort(entries.size.toShort())
        entries.forEachIndexed { i, entry ->
            out.putShort(entry.speaker.toShort())
            out.putLong(entry.uuid.mostSignificantBits)
            out.putLong(entry.uuid.leastSignificantBits)
            out.put(names[i].size.toByte())
            out.put(names[i])
        }
        return out.array()
    }

    fun decodeRoster(body: ByteArray): List<RosterEntry> {
        val buffer = ByteBuffer.wrap(body)
        val count = buffer.getShort().toInt() and 0xFFFF
        return List(count) {
            val speaker = buffer.getShort().toInt() and 0xFFFF
            val uuid = UUID(buffer.getLong(), buffer.getLong())
            val name = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
            RosterEntry(speaker, uuid, String(name, Charsets.UTF_8))
        }
    }
}
