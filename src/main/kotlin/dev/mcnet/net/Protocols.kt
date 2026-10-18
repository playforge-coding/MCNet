package dev.mcnet.net

import computer.iroh.Connection
import dev.mcnet.MCNet
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serves one incoming connection whose ALPN was registered through
 * [Protocols]. Runs as a child of the hosting job, so it is cancelled when
 * hosting stops. The handshake has already completed by the time it is called.
 */
fun interface ProtocolHandler {
    suspend fun accept(connection: Connection)
}

/**
 * The seam addons plug into: a registry of ALPNs the shared iroh endpoint
 * accepts, and the handler for each.
 *
 * MCNet itself only ever uses QUIC streams, one per Minecraft TCP session. An
 * addon that wants its own traffic (voice, for instance) registers an ALPN
 * here from its mod initializer, and the host's accept loop hands it every
 * connection that arrives with that ALPN. iroh refuses connections whose ALPN
 * is not advertised, so nothing reaches a handler that did not ask for it.
 *
 * Registering is cheap at any time. Before the endpoint binds, the ALPN simply
 * joins the list the endpoint is created with. After, it is pushed to the
 * live endpoint. Connections are only accepted while MCNet is hosting; a client
 * that merely joins never runs an accept loop.
 */
object Protocols {
    private class Registration(
        val alpn: ByteArray,
        val handler: ProtocolHandler,
    )

    private val registrations = CopyOnWriteArrayList<Registration>()

    fun register(
        alpn: ByteArray,
        handler: ProtocolHandler,
    ) {
        require(alpn.isNotEmpty()) { "ALPN must not be empty" }
        require(!alpn.contentEquals(MCNET_ALPN) && !alpn.contentEquals(MCNET_PROBE_ALPN)) {
            "ALPN ${alpn.decodeToString()} is reserved by MCNet"
        }
        if (registrations.any { it.alpn.contentEquals(alpn) }) {
            throw IllegalStateException("ALPN ${alpn.decodeToString()} is already registered")
        }
        registrations += Registration(alpn, handler)
        MCNet.logger.info("registered protocol {}", alpn.decodeToString())

        // A late registration still takes effect: the endpoint can change the
        // ALPNs it advertises after binding.
        IrohRuntime.endpointOrNull()?.let { endpoint ->
            runCatching { endpoint.setAlpns(alpns()) }
                .onFailure {
                    MCNet.logger.warn(
                        "could not advertise {} on the live endpoint",
                        alpn.decodeToString(),
                        it,
                    )
                }
        }
    }

    /** Every ALPN the endpoint should advertise, MCNet's own first. */
    fun alpns(): List<ByteArray> = listOf(MCNET_ALPN, MCNET_PROBE_ALPN) + registrations.map { it.alpn }

    fun handlerFor(alpn: ByteArray): ProtocolHandler? =
        registrations.firstOrNull { it.alpn.contentEquals(alpn) }?.handler
}
