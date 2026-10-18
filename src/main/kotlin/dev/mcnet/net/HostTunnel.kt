package dev.mcnet.net

import computer.iroh.Connection
import computer.iroh.Incoming
import dev.mcnet.MCNet
import dev.mcnet.MCNetEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hosting half: accepts iroh connections and splices each one onto the
 * Minecraft server listening on loopback.
 *
 * The Minecraft server is untouched and unaware. It sees connections arriving
 * from 127.0.0.1 exactly as if the players were sitting at the same machine,
 * which is why this works equally well for an integrated (singleplayer) server
 * opened to LAN and for a dedicated server.
 *
 * The accept loop is also the dispatcher for addon protocols: a connection
 * that arrives with an ALPN registered in [Protocols] is handed to that
 * handler instead of the Minecraft server.
 */
class HostTunnel(
    private val targetPort: Int,
    private val scope: CoroutineScope,
    private val lookup: AddressLookup = CompositeLookup.DEFAULT,
) {
    private var job: Job? = null
    private val connected = AtomicInteger()

    /**
     * Game connections per endpoint id. Usually one each, but a peer that
     * reconnects before its old connection times out briefly has two, and
     * [MCNetEvents.PEER_DISCONNECTED] must not fire until the last one is gone.
     */
    private val peerConnections = ConcurrentHashMap<String, Int>()

    /** Number of peers currently tunneled in. */
    val peerCount: Int get() = connected.get()

    /** Endpoint ids of the peers currently tunneled into the game. */
    val peers: Set<String> get() = peerConnections.keys.toSet()

    fun isPeer(endpointId: String): Boolean = peerConnections.containsKey(endpointId)

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        check(job == null) { "host tunnel already started" }
        job =
            scope.launch {
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
        val connection: Connection
        val alpn: ByteArray
        try {
            val accepting = incoming.accept()
            alpn = accepting.alpn()

            // A probe just wants to know the host is alive. Answering by
            // completing the handshake and hanging up keeps it out of the peer
            // count and out of the log, so a client refreshing its server list
            // does not look like players joining and leaving.
            if (alpn.contentEquals(MCNET_PROBE_ALPN)) {
                val probe = accepting.connect()
                runCatching { probe.close(0L, "probe acknowledged".toByteArray()) }
                return
            }

            connection = accepting.connect()
        } catch (e: Exception) {
            // Retransmitted datagrams and aborted handshakes surface here and
            // are not worth more than a debug line.
            MCNet.logger.debug("incoming iroh connection failed", e)
            return
        }

        if (!alpn.contentEquals(MCNET_ALPN)) {
            serveAddon(alpn, connection)
            return
        }

        val peer = runCatching { connection.remoteId().toString() }.getOrDefault("<unknown>")
        connected.incrementAndGet()
        MCNet.logger.info("peer {} connected ({} total)", peer, connected.get())
        if (peerConnections.merge(peer, 1, Int::plus) == 1) {
            MCNetEvents.PEER_CONNECTED.invoker().onPeerConnected(peer)
        }

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
            if (peerConnections.computeIfPresent(peer) { _, n -> if (n > 1) n - 1 else null } == null) {
                MCNetEvents.PEER_DISCONNECTED.invoker().onPeerDisconnected(peer)
            }
        }
    }

    /**
     * Hands a connection to the addon that registered its ALPN.
     *
     * The endpoint only advertises registered ALPNs, so a miss here means the
     * registration was removed between the handshake and now, or the bindings
     * accepted something they should not have. Either way, hang up.
     */
    private suspend fun serveAddon(
        alpn: ByteArray,
        connection: Connection,
    ) {
        val handler = Protocols.handlerFor(alpn)
        if (handler == null) {
            MCNet.logger.warn("no handler for ALPN {}; closing", alpn.decodeToString())
            runCatching { connection.close(1L, "unknown protocol".toByteArray()) }
            return
        }
        try {
            handler.accept(connection)
        } catch (e: Exception) {
            MCNet.logger.warn("protocol handler for {} failed", alpn.decodeToString(), e)
        } finally {
            runCatching { connection.close(0L, "done".toByteArray()) }
        }
    }

    private suspend fun CoroutineScope.openLocalSession(stream: computer.iroh.BiStream) {
        val socket =
            try {
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
        peerConnections.clear()
    }
}
