package dev.mcnet.voice.client

import computer.iroh.Connection
import computer.iroh.EndpointAddr
import dev.mcnet.net.IrohRuntime
import dev.mcnet.voice.Control
import dev.mcnet.voice.RosterEntry
import dev.mcnet.voice.VOICE_ALPN
import dev.mcnet.voice.VideoEvent
import dev.mcnet.voice.VideoStreams
import dev.mcnet.voice.VoiceServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a transport reports back to the session. All callbacks come from network threads. */
interface TransportListener {
    fun onDatagram(datagram: ByteArray)

    fun onVideo(
        speaker: Int,
        group: Int,
        event: VideoEvent,
    )

    fun onRoster(entries: List<RosterEntry>)

    fun onClosed(reason: String)
}

/** The path between this client and the relay, whichever side of the process boundary it is on. */
interface VoiceTransport {
    fun send(datagram: ByteArray)

    /**
     * Queues one event of this player's own video. False means a frame was
     * refused because the link is behind; the sender then starts a new group.
     */
    fun sendVideo(
        group: Int,
        event: VideoEvent,
    ): Boolean

    fun announceCamera(on: Boolean)

    fun watch(
        speaker: Int,
        on: Boolean,
    )

    fun close()
}

/**
 * A QUIC connection to the host over [VOICE_ALPN]: one control stream,
 * datagrams for audio, and unidirectional streams for video in both
 * directions.
 */
class RemoteTransport(
    private val host: EndpointAddr,
    private val listener: TransportListener,
) : VoiceTransport {
    private var connection: Connection? = null
    private var job: Job? = null

    /** Queues from the moment the transport exists; written once connected. */
    private val control = Control.Writer()

    @Volatile private var video: VideoStreams.Writer? = null

    @Volatile private var closedByUs = false

    /** Dials the host. Throws if it cannot; the session reports the reason. */
    suspend fun connect() {
        val endpoint = IrohRuntime.endpoint()
        val connection = endpoint.connect(host, VOICE_ALPN)
        this.connection = connection
        connection.setMaxConcurrentUniStreams(VideoStreams.MAX_OPEN_STREAMS)

        val bi = connection.openBi()
        val send = bi.send()
        // A stream is invisible to the acceptor until it carries data.
        Control.write(send, Control.PING)
        val video = VideoStreams.Writer(connection, IrohRuntime.scope)
        this.video = video

        job =
            IrohRuntime.scope.launch {
                try {
                    coroutineScope {
                        launch { control.run(send) }
                        launch {
                            val recv = bi.recv()
                            while (true) {
                                val message =
                                    Control.read(recv)
                                        ?: throw IllegalStateException("host closed the voice control stream")
                                if (message.type ==
                                    Control.ROSTER
                                ) {
                                    listener.onRoster(Control.decodeRoster(message.body))
                                }
                            }
                        }
                        launch {
                            while (true) {
                                delay(KEEPALIVE_MS)
                                control.send(Control.PING)
                            }
                        }
                        launch {
                            // One stream per group of pictures, each read by
                            // its own coroutine since groups can overlap, and
                            // put back in order by the sequencer.
                            val sequencer = VideoStreams.Sequencer(listener::onVideo)
                            while (true) {
                                val recv = connection.acceptUni()
                                launch { VideoStreams.read(recv, sequencer::accept) }
                            }
                        }
                        while (true) {
                            listener.onDatagram(connection.readDatagram())
                        }
                    }
                } catch (_: CancellationException) {
                    // Closed from our side; nothing to report.
                } catch (e: Exception) {
                    if (!closedByUs) {
                        val reason =
                            connection.closeReason()?.takeIf { it.isNotBlank() }
                                ?: e.message?.takeIf { it.isNotBlank() }
                                ?: e::class.simpleName
                                ?: "connection lost"
                        listener.onClosed(reason)
                    }
                } finally {
                    video.close()
                }
            }
    }

    override fun send(datagram: ByteArray) {
        // sendDatagram does not suspend and throws when the buffer is full.
        // Dropping a voice frame is the right response to back-pressure.
        runCatching { connection?.sendDatagram(datagram) }
    }

    override fun sendVideo(
        group: Int,
        event: VideoEvent,
    ): Boolean = video?.handle(0, group, event) ?: false

    override fun announceCamera(on: Boolean) = control.send(Control.CAMERA, Control.encodeCamera(on))

    override fun watch(
        speaker: Int,
        on: Boolean,
    ) = control.send(Control.WATCH, Control.encodeWatch(speaker, on))

    override fun close() {
        closedByUs = true
        control.close()
        video?.close()
        job?.cancel()
        job = null
        runCatching { connection?.close(0L, "mcnet-voice: left".toByteArray()) }
        connection = null
    }

    private companion object {
        /**
         * iroh closes a connection that stays silent for its idle timeout. A
         * listener who never talks would be dropped without this.
         */
        const val KEEPALIVE_MS = 10_000L
    }
}

/**
 * The host's own player, attached in-process to the relay running in the same
 * JVM. No sockets, no encoding of anything but the media itself.
 */
class LocalTransport(
    private val server: VoiceServer,
    player: VoiceServer.Player,
    listener: TransportListener,
) : VoiceTransport {
    private val participant =
        server.attachLocal(
            player,
            object : VoiceServer.Sink {
                override fun deliver(datagram: ByteArray) = listener.onDatagram(datagram)

                override fun video(
                    speaker: Int,
                    group: Int,
                    event: VideoEvent,
                ) = listener.onVideo(speaker, group, event)

                override fun roster(entries: List<RosterEntry>) = listener.onRoster(entries)

                override fun closed(reason: String) = listener.onClosed(reason)
            },
        )

    override fun send(datagram: ByteArray) = participant.inbound(datagram)

    override fun sendVideo(
        group: Int,
        event: VideoEvent,
    ): Boolean {
        participant.inboundVideo(group, event)
        return true
    }

    override fun announceCamera(on: Boolean) = participant.announceCamera(on)

    override fun watch(
        speaker: Int,
        on: Boolean,
    ) = participant.watch(speaker, on)

    override fun close() = server.detach(participant)
}
