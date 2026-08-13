package dev.mcnet.net

import computer.iroh.Connection
import computer.iroh.Incoming
import dev.mcnet.MCNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hosting half: accepts iroh connections and splices each one onto the
 * Minecraft server listening on loopback.
 *
 * The Minecraft server is untouched and unaware. It sees connections arriving
 * from 127.0.0.1 exactly as if the players were sitting at the same machine,
 * which is why this works equally well for an integrated (singleplayer) server
 * opened to LAN and for a dedicated server.
 */
class HostTunnel(
    private val targetPort: Int,
    private val scope: CoroutineScope,
    private val lookup: AddressLookup = CompositeLookup.DEFAULT,
) {
    private var job: Job? = null
    private val connected = AtomicInteger()

    /** Number of peers currently tunnelled in. */
    val peerCount: Int get() = connected.get()

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        check(job == null) { "host tunnel already started" }
        job = scope.launch {
            val endpoint = IrohRuntime.endpoint()
            lookup.publish(endpoint)
            MCNet.logger.info("hosting on endpoint id {} -> 127.0.0.1:{}", endpoint.id(), targetPort)

            while (isActive) {
                // Returns null once the endpoint is closed, which is the
                // normal way this loop ends.
                val incoming = endpoint.acceptNext() ?: break
                // Launched as a child of this job, so stop() tears every peer
                // down with it rather than leaving them running on the
                // long-lived runtime scope.
                launch { serve(incoming) }
            }
        }
    }

    private suspend fun CoroutineScope.serve(incoming: Incoming) {
        val connection = try {
            val accepting = incoming.accept()

            // A probe just wants to know the host is alive. Answering by
            // completing the handshake and hanging up keeps it out of the peer
            // count and out of the log, so a client refreshing its server list
            // does not look like players joining and leaving.
            if (accepting.alpn().contentEquals(MCNET_PROBE_ALPN)) {
                val probe = accepting.connect()
                runCatching { probe.close(0L, "probe acknowledged".toByteArray()) }
                return
            }

            accepting.connect()
        } catch (e: Exception) {
            // Retransmitted datagrams and aborted handshakes surface here and
            // are not worth more than a debug line.
            MCNet.logger.debug("incoming iroh connection failed", e)
            return
        }

        val peer = runCatching { connection.remoteId().toString() }.getOrDefault("<unknown>")
        connected.incrementAndGet()
        MCNet.logger.info("peer {} connected ({} total)", peer, connected.get())

        try {
            // One QUIC connection per peer, one bidirectional stream per TCP
            // session. A joining client opens at least two: the server-list
            // ping and the play session itself.
            while (true) {
                val stream = connection.acceptBi()
                launch { openLocalSession(stream) }
            }
        } catch (_: Exception) {
            // acceptBi throws when the peer goes away; that is the loop's exit.
        } finally {
            connected.decrementAndGet()
            MCNet.logger.info("peer {} disconnected ({} remaining)", peer, connected.get())
        }
    }

    private suspend fun CoroutineScope.openLocalSession(stream: computer.iroh.BiStream) {
        val socket = try {
            withContext(Dispatchers.IO) {
                Socket().apply {
                    connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 10_000)
                }
            }
        } catch (e: Exception) {
            MCNet.logger.warn("could not reach the local Minecraft server on port {}", targetPort, e)
            return
        }
        bridge(socket, stream, this)
    }

    fun stop() {
        job?.cancel()
        job = null
        connected.set(0)
    }
}
