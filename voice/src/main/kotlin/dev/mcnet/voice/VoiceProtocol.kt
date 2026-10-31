package dev.mcnet.voice

import computer.iroh.Connection
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The ALPN voice and video connections arrive on. Registered with the core's
 * protocol registry, so the host's accept loop routes these to [VoiceServer]
 * and never to the Minecraft server.
 *
 * Version 2 added cameras: a flag per roster entry, two control messages,
 * and video on unidirectional streams. Version 3 added proximity: the host
 * announces its voice range in a [Control.SETTINGS] message so that clients
 * can fade voices out where the relay stops forwarding them. Older clients
 * cannot connect, and since the addon is versioned with the core that is the
 * intended outcome.
 */
val VOICE_ALPN: ByteArray = "mcnet-voice/3".toByteArray(Charsets.US_ASCII)

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
    /** True while this participant is sending video. */
    val camera: Boolean = false,
)

/**
 * What happens on a video stream, in order. A group of pictures is one
 * keyframe and every frame that depends on it; each group travels on its own
 * QUIC stream, so these are the events of reading or writing one stream.
 *
 * Groups are numbered by the sender. A receiver only ever shows the newest
 * group, so an event for an older group arriving late is simply dropped.
 */
sealed interface VideoEvent {
    /** A new group starts. The first frame to follow is a keyframe. */
    data object Begin : VideoEvent

    class Frame(
        val keyframe: Boolean,
        val data: ByteArray,
    ) : VideoEvent

    /** The group is over, whether it finished or was cut off by a newer one. */
    data object End : VideoEvent
}

/** Reading helpers for the bindings' streams. */
object Streams {
    /**
     * The bindings' `read` may return fewer bytes than asked, and an empty
     * array means the stream finished. Null here means exactly that, a clean
     * finish; a reset surfaces as an exception.
     */
    suspend fun readExactly(
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
}

/**
 * Control messages ride a single bidirectional QUIC stream per connection,
 * each prefixed with a big-endian u16 length and a type byte. Everything that
 * must arrive, in order, goes here; audio and video do not.
 */
object Control {
    /** Host to client: the full participant list. Sent on every change. */
    const val ROSTER: Byte = 0x10

    /**
     * Host to client: how the host is running the call, currently just the
     * voice range in blocks, with 0 meaning everyone hears everyone. Sent
     * once on joining, before the first roster, and again if it changes.
     */
    const val SETTINGS: Byte = 0x11

    /** Either direction. Keeps the connection from idling out while nobody talks. */
    const val PING: Byte = 0x20
    const val PONG: Byte = 0x21

    /** Client to host: my camera is on (1) or off (0). Reflected in the roster. */
    const val CAMERA: Byte = 0x30

    /**
     * Client to host: forward (1) or stop forwarding (0) one participant's
     * video to me. Nothing is forwarded to a client that never asks, so a
     * client without video support costs the host no bandwidth.
     */
    const val WATCH: Byte = 0x31

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
        val header = Streams.readExactly(recv, 2) ?: return null
        val length = ByteBuffer.wrap(header).getShort().toInt() and 0xFFFF
        if (length < 1) return null
        val rest = Streams.readExactly(recv, length) ?: return null
        return Message(rest[0], rest.copyOfRange(1, rest.size))
    }

    fun encodeRoster(entries: List<RosterEntry>): ByteArray {
        val names =
            entries.map {
                it.name
                    .toByteArray(Charsets.UTF_8)
                    .take(255)
                    .toByteArray()
            }
        val size = 2 + entries.indices.sumOf { 2 + 16 + 1 + 1 + names[it].size }
        val out = ByteBuffer.allocate(size)
        out.putShort(entries.size.toShort())
        entries.forEachIndexed { i, entry ->
            out.putShort(entry.speaker.toShort())
            out.putLong(entry.uuid.mostSignificantBits)
            out.putLong(entry.uuid.leastSignificantBits)
            out.put(if (entry.camera) FLAG_CAMERA else 0)
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
            val flags = buffer.get().toInt()
            val name = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
            RosterEntry(speaker, uuid, String(name, Charsets.UTF_8), flags and FLAG_CAMERA.toInt() != 0)
        }
    }

    /**
     * Writes control messages in exactly the order they were queued. Queueing
     * never suspends and works before the stream exists; [run] does the
     * writing, from one coroutine, so two messages can never overtake each
     * other or interleave on the wire.
     */
    class Writer {
        private val queue = Channel<ByteArray>(Channel.UNLIMITED)

        fun send(
            type: Byte,
            body: ByteArray = ByteArray(0),
        ) {
            queue.trySend(frame(type, body))
        }

        /** Writes until [close] or until the stream breaks, which throws. */
        suspend fun run(stream: SendStream) {
            for (message in queue) stream.writeAll(message)
        }

        fun close() {
            queue.close()
        }
    }

    fun encodeSettings(range: Float): ByteArray = ByteBuffer.allocate(4).putFloat(range).array()

    /** The voice range in blocks, or null for a malformed body. */
    fun decodeSettings(body: ByteArray): Float? {
        if (body.size != 4) return null
        val range = ByteBuffer.wrap(body).getFloat()
        return if (!range.isFinite() || range < 0f) null else range
    }

    fun encodeCamera(on: Boolean): ByteArray = byteArrayOf(if (on) 1 else 0)

    fun decodeCamera(body: ByteArray): Boolean? = body.singleOrNull()?.let { it.toInt() != 0 }

    fun encodeWatch(
        speaker: Int,
        on: Boolean,
    ): ByteArray = byteArrayOf((speaker shr 8).toByte(), speaker.toByte(), if (on) 1 else 0)

    /** The speaker and whether to watch, or null for a malformed body. */
    fun decodeWatch(body: ByteArray): Pair<Int, Boolean>? {
        if (body.size != 3) return null
        val speaker = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return speaker to (body[2].toInt() != 0)
    }

    private const val FLAG_CAMERA: Byte = 1
}

/**
 * Video travels on unidirectional QUIC streams, one per group of pictures.
 *
 * Why not datagrams like audio: an AV1 frame at webcam bitrates is two to
 * six datagrams, and a lost one would corrupt every frame until the next
 * keyframe. A stream is reliable, and because a group is exactly the set of
 * frames that depend on one keyframe, nothing outside the stream is ever
 * needed to decode it. A sender ends each group before beginning the next,
 * so on a healthy link every stream finishes and every frame is delivered.
 * When a group is still in flight as the next one begins, the old stream is
 * reset and its late frames are simply never delivered. That is the whole
 * congestion story: a slow link skips groups, and never falls further behind
 * than one.
 *
 * Each stream starts with a header, then frames until the stream finishes:
 *
 * ```
 * u8 type | u16 speaker | u32 group
 * u32 length | u8 flags | av1 temporal unit    (repeated)
 * ```
 *
 * A client sends with speaker 0 and the host writes the sender's id into
 * the header of every stream it forwards.
 */
object VideoStreams {
    const val TYPE_GROUP: Byte = 1
    const val HEADER_SIZE = 7
    const val FRAME_HEADER_SIZE = 5

    /** Larger than any sane webcam keyframe; a guard against garbage, not a format limit. */
    const val MAX_FRAME = 1 shl 20

    /** The reset code for a stream abandoned in favor of a newer group. */
    const val RESET_SUPERSEDED = 1uL

    private const val FLAG_KEYFRAME = 1

    fun header(
        speaker: Int,
        group: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE)
            .put(TYPE_GROUP)
            .putShort(speaker.toShort())
            .putInt(group)
            .array()

    /** The speaker and group number, or null if this is not a group stream. */
    fun decodeHeader(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size != HEADER_SIZE || bytes[0] != TYPE_GROUP) return null
        val buffer = ByteBuffer.wrap(bytes, 1, HEADER_SIZE - 1)
        return (buffer.getShort().toInt() and 0xFFFF) to buffer.getInt()
    }

    fun frameHeader(
        keyframe: Boolean,
        length: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(FRAME_HEADER_SIZE)
            .putInt(length)
            .put(if (keyframe) FLAG_KEYFRAME.toByte() else 0)
            .array()

    /** The payload length and keyframe flag, or null for a length that cannot be right. */
    fun decodeFrameHeader(bytes: ByteArray): Pair<Int, Boolean>? {
        val buffer = ByteBuffer.wrap(bytes)
        val length = buffer.getInt()
        if (length < 0 || length > MAX_FRAME) return null
        return length to (buffer.get().toInt() and FLAG_KEYFRAME != 0)
    }

    /**
     * Reads one group stream to its end, reporting every event to [sink].
     * [sink] always sees [VideoEvent.Begin] first and [VideoEvent.End] last,
     * whether the stream finished, was reset, or carried garbage.
     */
    suspend fun read(
        recv: RecvStream,
        sink: (speaker: Int, group: Int, event: VideoEvent) -> Unit,
    ) {
        val header = Streams.readExactly(recv, HEADER_SIZE) ?: return
        val (speaker, group) = decodeHeader(header) ?: return
        sink(speaker, group, VideoEvent.Begin)
        try {
            while (true) {
                val frameHeader = Streams.readExactly(recv, FRAME_HEADER_SIZE) ?: break
                val (length, keyframe) = decodeFrameHeader(frameHeader) ?: break
                val payload = Streams.readExactly(recv, length) ?: break
                sink(speaker, group, VideoEvent.Frame(keyframe, payload))
            }
        } catch (_: Exception) {
            // Reset by the sender: a newer group superseded this one.
        } finally {
            sink(speaker, group, VideoEvent.End)
        }
    }

    /**
     * Writes groups to a connection, one stream per group per speaker, each
     * written by a coroutine of its own.
     *
     * [handle] never suspends, so the relay and the camera thread call it
     * directly. A group is cut off, its stream reset, in two cases: the
     * sender's next group begins while it is still being written, or it
     * falls a whole queue behind. Either happens at once, even if the write
     * is stuck waiting on flow control, because cancelling the writing
     * coroutine drops the native write it is suspended in. The receiver
     * resumes at the next keyframe, so a slow link is never more than one
     * group behind.
     */
    class Writer(
        private val connection: Connection,
        parent: CoroutineScope,
    ) {
        private class Open(
            val group: Int,
            val frames: Channel<VideoEvent.Frame>,
            val job: Job,
        )

        private val job = SupervisorJob(parent.coroutineContext[Job])
        private val scope = CoroutineScope(parent.coroutineContext + job)
        private val open = HashMap<Int, Open>()

        /**
         * Queues one event. False means a frame was refused because its
         * group was cut off, so nothing more of that group will be sent.
         */
        @Synchronized
        fun handle(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        ): Boolean {
            when (event) {
                VideoEvent.Begin -> {
                    // An unfinished older group gives way; the receiver will
                    // pick up at this group's keyframe.
                    open.remove(speaker)?.job?.cancel()
                    val frames = Channel<VideoEvent.Frame>(GROUP_QUEUE)
                    open[speaker] = Open(group, frames, scope.launch { write(speaker, group, frames) })
                    return true
                }

                is VideoEvent.Frame -> {
                    val current = open[speaker]?.takeIf { it.group == group } ?: return false
                    if (current.frames.trySend(event).isSuccess) return true
                    open.remove(speaker)
                    current.job.cancel()
                    return false
                }

                VideoEvent.End -> {
                    // Only the stream for this very group; a late End for an
                    // older group must not finish its successor. The writer
                    // drains what is queued, then finishes the stream.
                    if (open[speaker]?.group == group) open.remove(speaker)?.frames?.close()
                    return true
                }
            }
        }

        /** Cuts off everything still being written. For when the connection is going away. */
        fun close() {
            job.cancel()
        }

        private suspend fun write(
            speaker: Int,
            group: Int,
            frames: Channel<VideoEvent.Frame>,
        ) {
            var stream: SendStream? = null
            try {
                stream = connection.openUni()
                stream.writeAll(header(speaker, group))
                for (frame in frames) {
                    stream.writeAll(frameHeader(frame.keyframe, frame.data.size) + frame.data)
                }
                stream.finish()
            } catch (e: CancellationException) {
                // Cut off. The reset itself must still go out.
                withContext(NonCancellable) { stream?.let { runCatching { it.reset(RESET_SUPERSEDED) } } }
                throw e
            } catch (_: Exception) {
                // The connection is going away, or the peer stopped reading.
            }
        }
    }

    /**
     * Puts each speaker's groups back in order before they go to [sink].
     *
     * Every group arrives on its own stream and streams are read
     * concurrently, so the next group's Begin routinely overtakes the last
     * few frames of the one before it. Passed on as is, that would cut the
     * older group short wherever it goes next. This holds a newer group back
     * until the current one has ended, then releases it.
     *
     * A current group that neither ends nor progresses is given up once the
     * newer one has buffered [HOLD_LIMIT] frames (about a second), or as soon
     * as an even newer group appears, so a stalled stream costs at most that
     * much delay. Giving up sends the stalled group an End of its own.
     *
     * The first group seen for a speaker is taken as where they start. That
     * relies on a sender's groups being far apart compared to the time a
     * stream takes to open, which they are: a second at the very least.
     */
    class Sequencer(
        private val sink: (speaker: Int, group: Int, event: VideoEvent) -> Unit,
    ) {
        private class State(
            var group: Int,
        ) {
            var ended = false
            var next: Int? = null
            val held = ArrayList<VideoEvent>()
        }

        private val states = HashMap<Int, State>()

        @Synchronized
        fun accept(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        ) {
            val state = states[speaker]
            if (state == null) {
                states[speaker] = State(group)
                deliver(speaker, states.getValue(speaker), event)
                return
            }
            val next = state.next
            when {
                group < state.group -> {}

                group == state.group -> {
                    if (state.ended) return
                    deliver(speaker, state, event)
                    if (state.ended) release(speaker, state)
                }

                state.ended && next == null -> {
                    state.group = group
                    state.ended = false
                    deliver(speaker, state, event)
                }

                next == null -> {
                    state.next = group
                    state.held += event
                }

                group == next -> {
                    state.held += event
                    if (state.held.count { it is VideoEvent.Frame } > HOLD_LIMIT) {
                        giveUp(speaker, state)
                        release(speaker, state)
                    }
                }

                group > next -> {
                    // Two groups ahead: the current one and the held one are
                    // both stale. Start over with this one.
                    giveUp(speaker, state)
                    state.held.clear()
                    state.next = null
                    state.group = group
                    state.ended = false
                    deliver(speaker, state, event)
                }

                else -> {}
            }
        }

        private fun deliver(
            speaker: Int,
            state: State,
            event: VideoEvent,
        ) {
            sink(speaker, state.group, event)
            if (event == VideoEvent.End) state.ended = true
        }

        private fun giveUp(
            speaker: Int,
            state: State,
        ) {
            if (!state.ended) deliver(speaker, state, VideoEvent.End)
        }

        /** Makes the held group current and replays what it buffered. */
        private fun release(
            speaker: Int,
            state: State,
        ) {
            val next = state.next ?: return
            val held = state.held.toList()
            state.held.clear()
            state.next = null
            state.group = next
            state.ended = false
            held.forEach { deliver(speaker, state, it) }
        }

        private companion object {
            const val HOLD_LIMIT = 30
        }
    }

    /**
     * How many streams a peer may open at once, raised on both ends of the
     * connection. Groups finish quickly, but with several cameras and a
     * viewer slow to drain, QUIC's default of 100 is closer than it looks.
     */
    val MAX_OPEN_STREAMS = 1024uL

    /**
     * Frames queued per group before it is cut off. More than one group's
     * worth (90 frames), so the relay's replay to a late watcher always fits.
     */
    private const val GROUP_QUEUE = 128
}
