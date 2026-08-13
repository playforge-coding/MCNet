package dev.mcnet.net

import computer.iroh.Connection
import computer.iroh.EndpointAddr
import dev.mcnet.MCNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The joining half: presents the remote host as an ordinary server socket on
 * loopback, so the vanilla client can connect to it with no idea that iroh is
 * involved.
 *
 * This is what lets MCNet avoid touching Minecraft's networking stack at all.
 * Rather than intercepting packets, we hand the game an address it already
 * knows how to dial — `127.0.0.1:<ephemeral>` — and move the bytes underneath.
 */
class ClientTunnel(
    private val addr: EndpointAddr,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var server: ServerSocket? = null
    private var connection: Connection? = null
    private val activeSessions = AtomicInteger()

    /** The loopback address to point Minecraft at. Valid once [start] returns. */
    lateinit var localAddress: InetSocketAddress
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Dials the host and starts listening locally.
     *
     * The iroh connection is established up front rather than on first accept,
     * so that a bad join code or an unreachable host surfaces as an error the
     * player can read, instead of a generic Minecraft connection timeout.
     */
    suspend fun start(): InetSocketAddress {
        check(job == null) { "client tunnel already started" }

        val endpoint = IrohRuntime.endpoint()

        // Dialing your own endpoint id can never work, and it is easy to end up
        // doing it by accident. MCNet's identity lives in config/mcnet.json, so
        // any two instances that share *or copied* that file have the same
        // endpoint id — and duplicating an existing instance is the usual way
        // people make a second one. Without this check iroh fails much later
        // with something far less obvious.
        if (addr.id().toString() == endpoint.id().toString()) {
            throw IllegalArgumentException(
                "This instance already has that identity (${endpoint.id().fmtShort()}), so it " +
                    "would be dialing itself. Two instances share an identity when " +
                    "config/mcnet.json is shared or was copied between them. Fix: delete " +
                    "config/mcnet.json in one instance and restart it — MCNet will generate a " +
                    "fresh identity, and that instance gets a new join code.",
            )
        }

        val connection = endpoint.connect(addr, MCNET_ALPN)
        MCNet.logger.info("iroh connection established to host {}", addr.id())

        // Port 0 lets the OS pick; binding to the loopback interface keeps the
        // proxy off the local network.
        val socket = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            }
        }
        server = socket
        localAddress = socket.localSocketAddress as InetSocketAddress

        job = scope.launch { acceptLoop(socket, connection) }
        this.connection = connection
        startIdleWatchdog()
        MCNet.logger.info("local proxy listening on {}", localAddress)
        return localAddress
    }

    /**
     * Closes the tunnel once nothing has been using it for a while.
     *
     * Without this, a join that fails *after* the iroh connection is up — a
     * version mismatch, a whitelist rejection, the player backing out of the
     * connecting screen — leaves the QUIC connection open forever. The host
     * goes on counting that peer as connected, which is both a leak and a lie
     * in `/mcnet status`.
     *
     * The grace period covers the gap between binding the socket and Minecraft
     * actually dialing it.
     */
    private fun startIdleWatchdog() {
        scope.launch {
            delay(IDLE_GRACE_MS)
            while (isRunning) {
                if (activeSessions.get() > 0) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                MCNet.logger.info("MCNet tunnel idle with no sessions; closing")
                stop()
                return@launch
            }
        }
    }

    private suspend fun CoroutineScope.acceptLoop(socket: ServerSocket, connection: Connection) {
        while (isActive && !socket.isClosed) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Exception) {
                break // socket closed by stop()
            }
            // Child of the accept job, so stop() closes in-flight sessions too.
            launch {
                val stream = try {
                    connection.openBi()
                } catch (e: Exception) {
                    MCNet.logger.warn("host closed the connection", e)
                    runCatching { withContext(Dispatchers.IO) { client.close() } }
                    return@launch
                }
                activeSessions.incrementAndGet()
                try {
                    bridge(client, stream, this)
                } finally {
                    activeSessions.decrementAndGet()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
        // Closing the QUIC connection is what actually tells the host we are
        // gone; dropping the local socket alone would leave it counting us.
        runCatching { connection?.close(0L, "mcnet: client left".toByteArray()) }
        connection = null
        activeSessions.set(0)
    }

    private companion object {
        /** Time allowed for Minecraft to dial the local proxy after it opens. */
        const val IDLE_GRACE_MS = 30_000L
        const val IDLE_POLL_MS = 5_000L
    }
}
