package dev.mcnet.net

import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointTicket

/**
 * Turns a join code that a human pasted into an address iroh can dial.
 *
 * This is deliberately a seam rather than a couple of inline calls. iroh
 * resolves a bare endpoint id through whatever *address lookup* its endpoint
 * was built with; the published Kotlin bindings wire in n0's DNS resolver via
 * the `n0` preset, but the same role can be served by the BitTorrent Mainline
 * DHT (`iroh-mainline-address-lookup`, pkarr records signed by the endpoint
 * key). Swapping to the DHT means adding an implementation here and building
 * the endpoint against bindings that expose it. The tunnels, the commands and
 * the UI stay untouched.
 */
interface AddressLookup {
    /** Short name, used in log lines and command feedback. */
    val id: String

    /** True when [code] is in a format this lookup knows how to handle. */
    fun handles(code: String): Boolean

    /** Resolve [code], or throw if it is malformed. */
    suspend fun resolve(code: String): EndpointAddr

    /**
     * Announce [endpoint] so that peers can dial it by key alone.
     *
     * A no-op for lookups that are purely passive: a ticket carries its own
     * addresses, and the n0 preset publishes to n0's DNS server from inside
     * iroh without our involvement. A DHT-backed implementation would put a
     * signed pkarr record on Mainline here.
     */
    suspend fun publish(endpoint: Endpoint) {}
}

/**
 * Resolves a full iroh ticket: a base32 blob carrying the endpoint id, its home
 * relay and any directly reachable addresses.
 *
 * Self-contained, so it works without consulting any resolver at all. That
 * makes it the fallback when discovery infrastructure is unreachable. The cost is that a
 * ticket goes stale if the host's relay changes.
 */
object TicketLookup : AddressLookup {
    override val id = "ticket"

    override fun handles(code: String): Boolean = runCatching { EndpointTicket.fromString(code) }.isSuccess

    override suspend fun resolve(code: String): EndpointAddr = EndpointTicket.fromString(code).endpointAddr()
}

/**
 * Resolves a bare endpoint id, which is the host's permanent public key.
 *
 * The returned address carries no relay and no direct addresses, which is the
 * point: iroh treats that as "find this for me" and hands it to the endpoint's
 * address lookup. Because the key never changes, a saved MCNet server entry
 * keeps working across sessions and across network changes, which a ticket
 * cannot promise.
 */
object EndpointIdLookup : AddressLookup {
    override val id = "endpoint-id"

    override fun handles(code: String): Boolean = runCatching { EndpointId.fromString(code) }.isSuccess

    override suspend fun resolve(code: String): EndpointAddr =
        EndpointAddr(EndpointId.fromString(code), null, emptyList())
}

/** Tries each lookup in turn, so players can paste either form of join code. */
class CompositeLookup(
    private val delegates: List<AddressLookup>,
) : AddressLookup {
    override val id = delegates.joinToString("+") { it.id }

    override fun handles(code: String) = delegates.any { it.handles(code) }

    override suspend fun resolve(code: String): EndpointAddr {
        val trimmed = code.trim()
        val delegate =
            delegates.firstOrNull { it.handles(trimmed) }
                ?: throw IllegalArgumentException(
                    "Not a recognized MCNet join code. Expected an iroh ticket or an endpoint id.",
                )
        return delegate.resolve(trimmed)
    }

    override suspend fun publish(endpoint: Endpoint) = delegates.forEach { it.publish(endpoint) }

    companion object {
        /** The default: prefer a self-contained ticket, fall back to key lookup. */
        val DEFAULT = CompositeLookup(listOf(TicketLookup, EndpointIdLookup))
    }
}
