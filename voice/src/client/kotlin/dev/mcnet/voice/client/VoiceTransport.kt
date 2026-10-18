package dev.mcnet.voice.client

import computer.iroh.Connection
import computer.iroh.EndpointAddr
import computer.iroh.SendStream
import dev.mcnet.net.IrohRuntime
import dev.mcnet.voice.Control
import dev.mcnet.voice.RosterEntry
import dev.mcnet.voice.VOICE_ALPN
import dev.mcnet.voice.VoiceServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a transport reports back to the session. All callbacks come from network threads. */
interface TransportListener {
    fun onDatagram(datagram: ByteArray)

    fun onRoster(entries: List<RosterEntry>)

    fun onClosed(reason: String)
}

/** The path between this client and the relay, whichever side of the process boundary it is on. */
interface VoiceTransport {
    fun send(datagram: ByteArray)

    fun close()
}

/**
 * A QUIC connection to the host over [VOICE_ALPN]: one control stream plus
 * datagrams.
 */
class RemoteTransport(
    private val host: EndpointAddr,
    private val listener: TransportListener,
) : VoiceTransport {
    private var connection: Connection? = null
    private var job: Job? = null
    private val sendLock = Mutex()

    @Volatile private var closedByUs = false

    /** Dials the host. Throws if it cannot; the session reports the reason. */
    suspend fun connect() {
        val endpoint = IrohRuntime.endpoint()
        val connection = endpoint.connect(host, VOICE_ALPN)
        this.connection = connection

        val control = connection.openBi()
        val send = control.send()
        // A stream is invisible to the acceptor until it carries data.
        Control.write(send, Control.PING)

        job =
            IrohRuntime.scope.launch {
                try {
                    coroutineScope {
                        launch {
                            val recv = control.recv()
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
                                ping(send)
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
                }
            }
    }

    private suspend fun ping(send: SendStream) {
        sendLock.withLock { Control.write(send, Control.PING) }
    }

    override fun send(datagram: ByteArray) {
        // sendDatagram does not suspend and throws when the buffer is full.
        // Dropping a voice frame is the right response to back-pressure.
        runCatching { connection?.sendDatagram(datagram) }
    }

    override fun close() {
        closedByUs = true
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
 * JVM. No sockets, no encoding of anything but the audio itself.
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

                override fun roster(entries: List<RosterEntry>) = listener.onRoster(entries)

                override fun closed(reason: String) = listener.onClosed(reason)
            },
        )

    override fun send(datagram: ByteArray) = participant.inbound(datagram)

    override fun close() = server.detach(participant)
}
