package dev.mcnet.voice.client

import computer.iroh.EndpointAddr
import dev.mcnet.net.IrohRuntime
import dev.mcnet.voice.RosterEntry
import dev.mcnet.voice.VoiceFrames
import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.VoiceServer
import dev.mcnet.voice.client.audio.Microphone
import dev.mcnet.voice.client.audio.Playback
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One voice call, from the player's point of view: a transport to the relay,
 * a microphone feeding it, and playback draining it.
 *
 * The session is the only thing the UI talks to. It owns the audio threads
 * and restarts them when the player picks a different device.
 */
class VoiceSession private constructor(
    private val me: UUID,
) : TransportListener,
    Microphone.Sink {
    enum class State { CONNECTING, LIVE, FAILED, CLOSED }

    @Volatile var state: State = State.CONNECTING
        private set

    @Volatile var error: String? = null
        private set

    /** Everyone in the call, as the host last announced it. Includes this player. */
    @Volatile var roster: List<RosterEntry> = emptyList()
        private set

    @Volatile var microphone: Microphone? = null
        private set

    private var playback: Playback? = null
    private var transport: VoiceTransport? = null

    @Volatile private var speakerToPlayer: Map<Int, UUID> = emptyMap()
    private val lastHeard = ConcurrentHashMap<Int, Long>()

    val isTransmitting: Boolean get() = microphone?.transmitting == true

    /** Participants other than this player. */
    val others: List<RosterEntry> get() = roster.filter { it.uuid != me }

    /** Who has sent audio in the last quarter second. */
    fun speaking(): List<RosterEntry> {
        val now = System.currentTimeMillis()
        return roster.filter { now - (lastHeard[it.speaker] ?: 0L) < SPEAKING_WINDOW_MS }
    }

    fun isSpeaking(uuid: UUID): Boolean = speaking().any { it.uuid == uuid }

    /** Tears the audio down and brings it back on the configured devices. */
    fun restartAudio() {
        if (state != State.LIVE) return
        stopAudio()
        startAudio()
    }

    fun close() {
        state = State.CLOSED
        stopAudio()
        transport?.close()
        transport = null
    }

    private fun startAudio() {
        playback =
            Playback(VoiceClientConfig.speaker) { speaker ->
                val player = speakerToPlayer[speaker]
                val perPlayer = if (player == null) 1.0 else VoiceClientConfig.volumeOf(player)
                (perPlayer * VoiceClientConfig.volume).toFloat()
            }.start()
        microphone = Microphone(VoiceClientConfig.microphone, this).start()
    }

    private fun stopAudio() {
        microphone?.stop()
        microphone = null
        playback?.stop()
        playback = null
    }

    // Transport callbacks, on network threads.

    override fun onDatagram(datagram: ByteArray) {
        val frame = VoiceFrames.decode(datagram) ?: return
        if (frame.type == VoiceFrames.TYPE_AUDIO) lastHeard[frame.speaker] = System.currentTimeMillis()
        val player = speakerToPlayer[frame.speaker]
        if (player != null && VoiceClientConfig.isMuted(player)) return
        playback?.enqueue(frame)
    }

    override fun onRoster(entries: List<RosterEntry>) {
        val previous = speakerToPlayer.keys
        roster = entries
        speakerToPlayer = entries.associate { it.speaker to it.uuid }
        (previous - speakerToPlayer.keys).forEach { gone ->
            playback?.retire(gone)
            lastHeard.remove(gone)
        }
    }

    override fun onClosed(reason: String) {
        VoiceLog.logger.info("voice session ended: {}", reason)
        error = reason
        state = State.FAILED
        stopAudio()
    }

    // Microphone callbacks, on the capture thread.

    override fun frame(
        opus: ByteArray,
        sequence: Int,
    ) {
        transport?.send(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, sequence, opus))
    }

    override fun endOfBurst(sequence: Int) {
        transport?.send(VoiceFrames.encode(VoiceFrames.TYPE_END, 0, sequence, ByteArray(0)))
    }

    companion object {
        private const val SPEAKING_WINDOW_MS = 250L

        /** Dials [host] in the background; the returned session reports progress through [state]. */
        fun startRemote(
            me: UUID,
            host: EndpointAddr,
        ): VoiceSession {
            val session = VoiceSession(me)
            val transport = RemoteTransport(host, session)
            session.transport = transport
            IrohRuntime.scope.launch {
                try {
                    transport.connect()
                    if (session.state == State.CONNECTING) {
                        session.state = State.LIVE
                        session.startAudio()
                        VoiceLog.logger.info("voice connected to {}", host.id().fmtShort())
                    }
                } catch (e: Exception) {
                    VoiceLog.logger.warn("voice connection failed", e)
                    session.error = e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "error")
                    session.state = State.FAILED
                }
            }
            return session
        }

        /** Joins the relay running in this process, for the player who is hosting. */
        fun startLocal(
            server: VoiceServer,
            player: VoiceServer.Player,
        ): VoiceSession {
            val session = VoiceSession(player.uuid)
            session.transport = LocalTransport(server, player, session)
            session.state = State.LIVE
            session.startAudio()
            VoiceLog.logger.info("voice attached to the local relay")
            return session
        }
    }
}
