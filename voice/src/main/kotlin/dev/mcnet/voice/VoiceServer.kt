package dev.mcnet.voice

import computer.iroh.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host side of voice and video: one relay that every participant talks to.
 *
 * A star rather than a mesh, on purpose. With no proximity every frame goes
 * to everyone anyway, and when proximity does arrive the server is the one
 * place that knows every player's position, so it becomes a filter in
 * [relay] rather than a redesign.
 *
 * Identity is the iroh endpoint id, which the QUIC handshake has already
 * authenticated by the time [accept] runs. [bind] ties an endpoint id to a
 * player, and only a bound endpoint is let in; the binding itself arrives
 * over the player's authenticated game session with a signature proving the
 * player holds the key. So the join code remains the only credential, and
 * nobody can speak under another player's name.
 *
 * Audio goes to everyone. Video goes only to participants who asked to
 * watch that sender, and the relay keeps each sender's current group of
 * pictures so a new watcher can start decoding at once instead of waiting
 * for the next keyframe.
 *
 * The host's own player cannot dial their own endpoint, so they join through
 * [attachLocal] instead, in-process. On a dedicated server that path is
 * simply never used.
 *
 * Nothing in here knows about Minecraft; the tests run it against bare iroh
 * endpoints.
 */
class VoiceServer(
    private val scope: CoroutineScope,
) {
    /** What the game session told us about an endpoint. */
    data class Player(
        val uuid: UUID,
        val name: String,
    )

    /** Where a participant's frames and roster updates go. */
    interface Sink {
        fun deliver(datagram: ByteArray)

        fun video(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        )

        fun roster(entries: List<RosterEntry>)

        fun closed(reason: String)
    }

    /**
     * Someone in the call. Remote participants are backed by a QUIC
     * connection; the local one by direct method calls.
     */
    abstract inner class Participant(
        val speaker: Int,
        val player: Player,
    ) {
        @Volatile var camera: Boolean = false
            internal set

        /** Speakers whose video this participant wants. */
        internal val watching: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        /** Guards [group]; also held while fanning this participant's video out, so watchers see events in order. */
        internal val videoLock = Any()
        internal var group: Group? = null

        val entry: RosterEntry get() = RosterEntry(speaker, player.uuid, player.name, camera)

        /** Called by the transport when this participant has something to say. */
        fun inbound(datagram: ByteArray) = relay(this, datagram)

        fun inboundVideo(
            group: Int,
            event: VideoEvent,
        ) = relayVideo(this, group, event)

        fun announceCamera(on: Boolean) = setCamera(this, on)

        fun watch(
            speaker: Int,
            on: Boolean,
        ) = this@VoiceServer.watch(this, speaker, on)

        abstract fun deliver(datagram: ByteArray)

        abstract fun deliverVideo(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        )

        abstract fun sendRoster(entries: List<RosterEntry>)

        abstract fun close(reason: String)

        /** Releases what this participant holds. Safe to call more than once. */
        open fun dispose() {}
    }

    /** One sender's current group of pictures, kept for watchers who arrive mid-group. */
    internal class Group(
        val number: Int,
    ) {
        val frames = ArrayList<VideoEvent.Frame>()
        var bytes = 0

        /** Too big to replay; late watchers wait for the next group instead. */
        var overflowed = false
    }

    private val bindings = ConcurrentHashMap<String, Player>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Player>>()
    private val participants = ConcurrentHashMap<UUID, Participant>()
    private val nextSpeaker = AtomicInteger(1)

    /** Everyone currently in the call. */
    val roster: List<RosterEntry> get() = participants.values.map { it.entry }.sortedBy { it.speaker }

    val isEmpty: Boolean get() = participants.isEmpty()

    /** Records that the player behind [endpointId] is [player]. Unblocks a pending [accept]. */
    fun bind(
        endpointId: String,
        player: Player,
    ) {
        bindings[endpointId] = player
        waiters.remove(endpointId)?.complete(player)
    }

    /** Called when a player leaves the game. Drops the binding and hangs up on them. */
    fun playerLeft(uuid: UUID) {
        bindings.entries.removeIf { it.value.uuid == uuid }
        participants[uuid]?.let { participant ->
            participant.close("left the game")
            leave(participant)
        }
    }

    /**
     * Serves one incoming connection until it ends. Registered as the handler
     * for [VOICE_ALPN], so it runs as a child of the hosting job and is
     * cancelled when hosting stops.
     */
    suspend fun accept(connection: Connection) {
        val endpointId = connection.remoteId().toString()
        val player = awaitBinding(endpointId)
        if (player == null) {
            VoiceLog.logger.info("voice connection from {} is not a player here; refusing", endpointId)
            runCatching { connection.close(2L, "no player bound to this endpoint".toByteArray()) }
            return
        }

        // The client opens the control stream and sends a PING immediately,
        // because a QUIC stream only becomes visible to the acceptor once it
        // carries data.
        val control = connection.acceptBi()
        connection.setMaxConcurrentUniStreams(VideoStreams.MAX_OPEN_STREAMS)
        val participant = RemoteParticipant(nextSpeaker.getAndIncrement() and 0xFFFF, player, connection)
        join(participant)

        try {
            coroutineScope {
                // Roster updates queued by join() above go out as soon as
                // this starts.
                val writer = launch { participant.control.run(control.send()) }
                val reader =
                    launch {
                        val recv = control.recv()
                        while (true) {
                            val message = Control.read(recv) ?: break
                            when (message.type) {
                                Control.PING -> {
                                    participant.control.send(Control.PONG)
                                }

                                Control.CAMERA -> {
                                    Control.decodeCamera(message.body)?.let(participant::announceCamera)
                                }

                                Control.WATCH -> {
                                    Control.decodeWatch(message.body)?.let { (speaker, on) ->
                                        participant.watch(speaker, on)
                                    }
                                }
                            }
                        }
                    }
                val video =
                    launch {
                        // Each group arrives on its own stream, and a new
                        // group may start before the old stream is drained,
                        // so every stream gets its own reader, and the
                        // sequencer puts the groups back in order.
                        val sequencer =
                            VideoStreams.Sequencer {
                                _,
                                group,
                                event,
                                ->
                                participant.inboundVideo(group, event)
                            }
                        while (true) {
                            val recv = connection.acceptUni()
                            launch { VideoStreams.read(recv, sequencer::accept) }
                        }
                    }
                try {
                    while (true) {
                        participant.inbound(connection.readDatagram())
                    }
                } finally {
                    writer.cancel()
                    reader.cancel()
                    video.cancel()
                }
            }
        } catch (e: Exception) {
            // The peer going away surfaces here; it is the normal exit.
            VoiceLog.logger.debug("voice connection from {} ended", player.name, e)
        } finally {
            leave(participant)
            // Unconditionally: a participant replaced by a reconnect is no
            // longer in the map, so leave() does nothing for it.
            participant.dispose()
        }
    }

    /** Joins the host's own player, in-process. */
    fun attachLocal(
        player: Player,
        sink: Sink,
    ): Participant {
        val participant = LocalParticipant(nextSpeaker.getAndIncrement() and 0xFFFF, player, sink)
        join(participant)
        return participant
    }

    /** Removes a participant that is leaving on its own, the local player disabling voice for instance. */
    fun detach(participant: Participant) {
        leave(participant)
    }

    /** Hangs up on everyone. Bindings survive, so re-hosting lets the same players back in. */
    fun stop() {
        val all = participants.values.toList()
        participants.clear()
        all.forEach {
            it.close("voice stopped")
            it.dispose()
        }
        waiters.values.forEach { it.cancel() }
        waiters.clear()
    }

    private suspend fun awaitBinding(endpointId: String): Player? {
        bindings[endpointId]?.let { return it }
        val waiter = waiters.computeIfAbsent(endpointId) { CompletableDeferred() }
        return try {
            withTimeoutOrNull(BINDING_TIMEOUT_MS) { waiter.await() }
        } finally {
            waiters.remove(endpointId, waiter)
        }
    }

    private fun join(participant: Participant) {
        // A reconnect replaces the previous session for the same player.
        participants.put(participant.player.uuid, participant)?.let { previous ->
            previous.close("replaced by a new connection")
            endVideo(previous)
        }
        VoiceLog.logger.info("{} joined voice as speaker {}", participant.player.name, participant.speaker)
        broadcastRoster()
    }

    private fun leave(participant: Participant) {
        if (participants.remove(participant.player.uuid, participant)) {
            VoiceLog.logger.info("{} left voice", participant.player.name)
            endVideo(participant)
            participants.values.forEach { it.watching.remove(participant.speaker) }
            broadcastRoster()
        }
    }

    private fun broadcastRoster() {
        val entries = roster
        participants.values.forEach { it.sendRoster(entries) }
    }

    /**
     * Fans one participant's frame out to everyone else. This is the function
     * proximity would filter in.
     */
    private fun relay(
        from: Participant,
        datagram: ByteArray,
    ) {
        if (!VoiceFrames.isWellFormed(datagram)) return
        VoiceFrames.restamp(datagram, from.speaker)
        participants.values.forEach { target ->
            if (target !== from) target.deliver(datagram)
        }
    }

    /**
     * Forwards one video event to everyone watching [from], and keeps the
     * current group so a watcher who arrives mid-group can be caught up.
     */
    private fun relayVideo(
        from: Participant,
        group: Int,
        event: VideoEvent,
    ) {
        synchronized(from.videoLock) {
            when (event) {
                VideoEvent.Begin -> {
                    from.group = Group(group)
                }

                is VideoEvent.Frame -> {
                    from.group?.takeIf { it.number == group }?.let { current ->
                        if (current.bytes + event.data.size > GROUP_BUFFER_LIMIT) {
                            current.overflowed = true
                            current.frames.clear()
                        } else if (!current.overflowed) {
                            current.frames += event
                            current.bytes += event.data.size
                        }
                    }
                }

                VideoEvent.End -> {
                    if (from.group?.number == group) from.group = null
                }
            }
            watchersOf(from).forEach { it.deliverVideo(from.speaker, group, event) }
        }
    }

    private fun setCamera(
        who: Participant,
        on: Boolean,
    ) {
        if (who.camera == on) return
        who.camera = on
        if (!on) endVideo(who)
        broadcastRoster()
    }

    private fun watch(
        who: Participant,
        speaker: Int,
        on: Boolean,
    ) {
        val sender = participants.values.firstOrNull { it.speaker == speaker } ?: return
        if (sender === who) return
        synchronized(sender.videoLock) {
            if (on) {
                if (!who.watching.add(speaker)) return
                // Catch the newcomer up from the keyframe, so they do not
                // stare at a blank tile until the next group.
                sender.group?.takeIf { !it.overflowed }?.let { current ->
                    who.deliverVideo(speaker, current.number, VideoEvent.Begin)
                    current.frames.forEach { who.deliverVideo(speaker, current.number, it) }
                }
            } else if (who.watching.remove(speaker)) {
                sender.group?.let { who.deliverVideo(speaker, it.number, VideoEvent.End) }
            }
        }
    }

    /** Closes out an in-progress group for a sender whose video stopped. */
    private fun endVideo(sender: Participant) {
        synchronized(sender.videoLock) {
            val current = sender.group ?: return
            sender.group = null
            watchersOf(sender).forEach { it.deliverVideo(sender.speaker, current.number, VideoEvent.End) }
        }
    }

    private fun watchersOf(sender: Participant): List<Participant> =
        participants.values.filter { it !== sender && sender.speaker in it.watching }

    private inner class RemoteParticipant(
        speaker: Int,
        player: Player,
        private val connection: Connection,
    ) : Participant(speaker, player) {
        /** Control messages to this participant, written in order by [accept]. */
        val control = Control.Writer()

        /**
         * Video to this participant. A group that falls a whole queue behind
         * on this participant's link is cut off for them alone, and they pick
         * up again at that sender's next keyframe.
         */
        private val video = VideoStreams.Writer(connection, scope)

        override fun deliver(datagram: ByteArray) {
            // Non-blocking. If the send buffer is full the frame is dropped,
            // which is exactly what a voice packet should do under pressure.
            runCatching { connection.sendDatagram(datagram) }
        }

        override fun deliverVideo(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        ) {
            video.handle(speaker, group, event)
        }

        override fun sendRoster(entries: List<RosterEntry>) {
            control.send(Control.ROSTER, Control.encodeRoster(entries))
        }

        override fun close(reason: String) {
            runCatching { connection.close(0L, reason.toByteArray()) }
        }

        override fun dispose() {
            video.close()
            control.close()
        }
    }

    private inner class LocalParticipant(
        speaker: Int,
        player: Player,
        private val sink: Sink,
    ) : Participant(speaker, player) {
        override fun deliver(datagram: ByteArray) = sink.deliver(datagram)

        override fun deliverVideo(
            speaker: Int,
            group: Int,
            event: VideoEvent,
        ) = sink.video(speaker, group, event)

        override fun sendRoster(entries: List<RosterEntry>) = sink.roster(entries)

        // Callers have already taken this participant out of the map.
        override fun close(reason: String) = sink.closed(reason)
    }

    private companion object {
        /**
         * How long a voice connection may wait for the game session to vouch
         * for it. The hello payload and the QUIC handshake race; the payload
         * normally wins by a round trip, but a loaded server can reorder them.
         */
        const val BINDING_TIMEOUT_MS = 5_000L

        /** A group larger than this is not replayed to late watchers. Generous for webcam bitrates. */
        const val GROUP_BUFFER_LIMIT = 2 * 1024 * 1024
    }
}
