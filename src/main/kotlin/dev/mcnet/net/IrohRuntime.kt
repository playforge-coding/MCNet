package dev.mcnet.net

import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointTicket
import computer.iroh.SecretKey
import dev.mcnet.MCNet
import dev.mcnet.MCNetConfig
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the process-wide iroh endpoint and the coroutine scope every tunnel runs
 * on.
 *
 * The endpoint is bound lazily: players who never host or join should not pay
 * for a QUIC socket, a relay handshake and a 27 MB native library being paged
 * in. Once bound it is shared, because a single endpoint can host and dial
 * simultaneously and each one costs a UDP socket plus relay registration.
 */
object IrohRuntime {
    /**
     * SupervisorJob so one failed tunnel cannot cancel unrelated ones, and the
     * IO dispatcher because every leaf here either blocks on a socket or waits
     * on a native future.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("mcnet"))

    private val lock = Mutex()
    private var endpoint: Endpoint? = null

    /** Binds the endpoint on first use and returns the shared instance. */
    suspend fun endpoint(): Endpoint =
        lock.withLock {
            endpoint?.takeIf { !it.isClosed() } ?: bind().also { endpoint = it }
        }

    /** The endpoint if one is already bound, without binding one. */
    fun endpointOrNull(): Endpoint? = endpoint?.takeIf { !it.isClosed() }

    private suspend fun bind(): Endpoint {
        val secret = MCNetConfig.secretKey()

        // Built through EndpointBuilder rather than EndpointOptions because the
        // address lookup is attached at bind time, and only the builder exposes
        // it.
        val builder = EndpointBuilder()
        // The n0 preset supplies the default relay servers plus the resolver
        // that turns a bare endpoint id into an address.
        builder.applyN0()
        builder.secretKey(secret.toBytes())
        builder.alpns(listOf(MCNET_ALPN, MCNET_PROBE_ALPN))

        if (MCNetConfig.addressLookup == MCNetConfig.AddressLookupMode.DHT) {
            MainlineDht.attach(builder, publish = MCNetConfig.dhtPublish)
        }

        val endpoint = builder.bind()
        MCNet.logger.info("iroh endpoint bound, endpoint id {}", endpoint.id())
        return endpoint
    }

    /**
     * A self-contained join code for this endpoint: id, home relay and the
     * direct addresses discovered so far.
     */
    suspend fun ticket(): String = EndpointTicket.fromAddr(endpoint().addr()).toString()

    /**
     * The host's permanent public key. Unlike a ticket this never changes, so a
     * saved server entry survives the host moving networks.
     */
    suspend fun endpointId(): String = endpoint().id().toString()

    /**
     * This instance's own endpoint id, derived straight from the stored secret
     * key so it is available without binding an endpoint.
     *
     * Shown in the UI because two instances that share or copied
     * config/mcnet.json share an identity, and that is otherwise invisible
     * until a join mysteriously fails.
     */
    fun localIdShort(): String = runCatching { MCNetConfig.secretKey().`public`().fmtShort() }.getOrDefault("unknown")

    /**
     * Waits until the endpoint has a working path to the network. Worth doing
     * before handing a player a ticket, since a ticket minted before the relay
     * handshake completes may not list a relay yet.
     */
    suspend fun awaitOnline() {
        endpoint().online()
    }

    /** Closes the endpoint and stops every tunnel. Called on game/server shutdown. */
    fun shutdown() {
        val current = endpoint ?: return
        endpoint = null
        runCatching {
            // Blocking is acceptable here: this runs on shutdown, and skipping
            // the graceful close would leave peers waiting on a dead relay
            // path instead of seeing an immediate disconnect.
            runBlocking { current.shutdown() }
        }.onFailure { MCNet.logger.warn("iroh endpoint did not close cleanly", it) }
        scope.cancel()
    }

    /** Generates a fresh endpoint identity. */
    fun generateSecretKey(): SecretKey = SecretKey.generate()
}
