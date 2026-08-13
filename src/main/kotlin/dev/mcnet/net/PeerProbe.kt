package dev.mcnet.net

import dev.mcnet.MCNet
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Answers "is this host up right now?" for the saved peer list.
 *
 * A probe is a real iroh connection over [MCNET_PROBE_ALPN], immediately
 * closed. That is a stronger signal than it looks: it succeeds only if the
 * address lookup resolved the key, a network path was found (hole punched or
 * relayed) *and* something on the far end is running MCNet and willing to talk.
 * Any one of those failing means the player could not have joined anyway.
 *
 * What it deliberately does not report is who is playing or what the MOTD says.
 * Getting those would mean speaking the Minecraft status protocol through the
 * tunnel, and keeping MCNet free of protocol knowledge is what makes it
 * survive game updates.
 */
object PeerProbe {

    enum class Status { ONLINE, OFFLINE, CHECKING }

    private const val TIMEOUT_MS = 10_000L

    /**
     * Checks reachability of [peerId].
     *
     * Never throws: an unreachable host is an ordinary answer, not an error.
     */
    suspend fun isOnline(peerId: String): Boolean = try {
        val endpoint = IrohRuntime.endpoint()

        // Probing yourself would connect to your own endpoint and report a
        // misleading "online" for an entry you can never actually join.
        if (peerId == endpoint.id().toString()) {
            false
        } else {
            withTimeout(TIMEOUT_MS) {
                val addr = CompositeLookup.DEFAULT.resolve(peerId)
                val connection = endpoint.connect(addr, MCNET_PROBE_ALPN)
                runCatching { connection.close(0L, "probe complete".toByteArray()) }
                true
            }
        }
    } catch (_: TimeoutCancellationException) {
        false
    } catch (e: Exception) {
        MCNet.logger.debug("probe of {} failed", peerId, e)
        false
    }
}
