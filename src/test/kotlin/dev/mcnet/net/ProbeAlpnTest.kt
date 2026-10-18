package dev.mcnet.net

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Verifies the mechanism the saved peer list depends on: telling a liveness
 * probe apart from a real player before either is counted.
 *
 * Two things could break this and neither is obvious by inspection. First,
 * `Accepting.alpn()` has to be readable *without* consuming the handshake, or
 * the host could not inspect an ALPN and then still accept the connection.
 * Second, the endpoint has to advertise both ALPNs for a probe to be accepted
 * at all.
 */
class ProbeAlpnTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun bind(vararg alpns: ByteArray) =
        Endpoint.bind(
            EndpointOptions(preset = presetMinimal(), alpns = alpns.toList()),
        )

    @Test
    fun `host can read the alpn and still accept the connection`() =
        runBlocking {
            val host = bind(MCNET_ALPN, MCNET_PROBE_ALPN)
            val client = bind(MCNET_ALPN, MCNET_PROBE_ALPN)

            try {
                withTimeout(60_000) {
                    val seenAlpn = CompletableDeferred<ByteArray>()
                    val accepted = CompletableDeferred<Boolean>()

                    scope.launch {
                        val incoming = host.acceptNext() ?: return@launch
                        val accepting = incoming.accept()
                        // Reading the ALPN first is what lets HostTunnel route a
                        // probe away from the player path.
                        seenAlpn.complete(accepting.alpn())
                        // The handshake must still be completable afterwards.
                        val connection = accepting.connect()
                        accepted.complete(true)
                        runCatching { connection.close(0L, "done".toByteArray()) }
                    }

                    client.connect(host.addr(), MCNET_PROBE_ALPN)

                    assertContentEquals(
                        MCNET_PROBE_ALPN,
                        seenAlpn.await(),
                        "host should observe the probe ALPN, not the player ALPN",
                    )
                    assertTrue(accepted.await(), "reading the ALPN must not consume the handshake")
                }
            } finally {
                runCatching { client.shutdown() }
                runCatching { host.shutdown() }
            }
        }

    @Test
    fun `a real join is distinguishable from a probe`() =
        runBlocking {
            val host = bind(MCNET_ALPN, MCNET_PROBE_ALPN)
            val client = bind(MCNET_ALPN, MCNET_PROBE_ALPN)

            try {
                withTimeout(60_000) {
                    val seenAlpn = CompletableDeferred<ByteArray>()
                    scope.launch {
                        val incoming = host.acceptNext() ?: return@launch
                        val accepting = incoming.accept()
                        seenAlpn.complete(accepting.alpn())
                        accepting.connect()
                    }

                    client.connect(host.addr(), MCNET_ALPN)

                    assertContentEquals(MCNET_ALPN, seenAlpn.await())
                    assertTrue(
                        !seenAlpn.await().contentEquals(MCNET_PROBE_ALPN),
                        "a player connection must not be mistaken for a probe",
                    )
                }
            } finally {
                runCatching { client.shutdown() }
                runCatching { host.shutdown() }
            }
        }
}
