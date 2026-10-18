package dev.mcnet.voice

import computer.iroh.Connection
import computer.iroh.SendStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The host side of voice: one relay that every participant talks to.
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
        val entry: RosterEntry get() = RosterEntry(speaker, player.uuid, player.name)

        /** Called by the transport when this participant has something to say. */
        fun inbound(datagram: ByteArray) = relay(this, datagram)

        abstract fun deliver(datagram: ByteArray)

        abstract fun sendRoster(entries: List<RosterEntry>)

        abstract fun close(reason: String)
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
        participants.remove(uuid)?.let {
            it.close("left the game")
            broadcastRoster()
        }
    }

    /**
     * Serves one incoming voice connection until it ends. Registered as the
     * handler for [VOICE_ALPN], so it runs as a child of the hosting job and is
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
        val participant =
            RemoteParticipant(nextSpeaker.getAndIncrement() and 0xFFFF, player, connection, control.send())
        join(participant)

        try {
            coroutineScope {
                val reader =
                    launch {
                        val recv = control.recv()
                        while (true) {
                            val message = Control.read(recv) ?: break
                            if (message.type == Control.PING) participant.sendControl(Control.PONG)
                        }
                    }
                try {
                    while (true) {
                        participant.inbound(connection.readDatagram())
                    }
                } finally {
                    reader.cancel()
                }
            }
        } catch (e: Exception) {
            // The peer going away surfaces here; it is the normal exit.
            VoiceLog.logger.debug("voice connection from {} ended", player.name, e)
        } finally {
            leave(participant)
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
        all.forEach { it.close("voice stopped") }
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
        participants.put(participant.player.uuid, participant)?.close("replaced by a new connection")
        VoiceLog.logger.info("{} joined voice as speaker {}", participant.player.name, participant.speaker)
        broadcastRoster()
    }

    private fun leave(participant: Participant) {
        if (participants.remove(participant.player.uuid, participant)) {
            VoiceLog.logger.info("{} left voice", participant.player.name)
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

    private inner class RemoteParticipant(
        speaker: Int,
        player: Player,
        private val connection: Connection,
        private val send: SendStream,
    ) : Participant(speaker, player) {
        private val sendLock = Mutex()

        override fun deliver(datagram: ByteArray) {
            // Non-blocking. If the send buffer is full the frame is dropped,
            // which is exactly what a voice packet should do under pressure.
            runCatching { connection.sendDatagram(datagram) }
        }

        override fun sendRoster(entries: List<RosterEntry>) {
            sendControl(Control.ROSTER, Control.encodeRoster(entries))
        }

        fun sendControl(
            type: Byte,
            body: ByteArray = ByteArray(0),
        ): Job =
            scope.launch {
                // Serialized per stream: two interleaved writes would corrupt
                // the length-prefixed framing.
                runCatching { sendLock.withLock { Control.write(send, type, body) } }
            }

        override fun close(reason: String) {
            runCatching { connection.close(0L, reason.toByteArray()) }
        }
    }

    private inner class LocalParticipant(
        speaker: Int,
        player: Player,
        private val sink: Sink,
    ) : Participant(speaker, player) {
        override fun deliver(datagram: ByteArray) = sink.deliver(datagram)

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
    }
}
