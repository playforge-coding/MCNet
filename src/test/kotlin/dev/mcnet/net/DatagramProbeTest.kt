package dev.mcnet.net

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Records what iroh's unreliable datagrams can carry.
 *
 * This matters for Bedrock support: Bedrock speaks RakNet over UDP, which
 * implements its own reliability, so tunnelling it over a *reliable* QUIC
 * stream would add head-of-line blocking to a game protocol built to tolerate
 * loss. Datagrams are the right primitive — but only if RakNet's negotiated
 * MTU fits inside one.
 *
 * The headline finding is that **it is not a fixed budget**.
 * `maxDatagramSize()` reports the current path-MTU estimate, which QUIC raises
 * as discovery proceeds: measured at 1162 early in a connection and 1288 once
 * settled, on the same loopback path. So RakNet's 1200-byte MTU tier cannot be
 * assumed to fit — a tunnel would have to clamp well under the floor, or wear
 * RakNet dropping to its 576 tier.
 *
 * The assertion therefore guards a conservative floor, not the peak.
 */
class DatagramProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun bind() = Endpoint.bind(
        EndpointOptions(preset = presetMinimal(), alpns = listOf(MCNET_ALPN)),
    )

    @Test
    fun `datagrams carry a RakNet-sized payload`() = runBlocking {
        val host = bind()
        val client = bind()

        try {
            withTimeout(30_000) {
                val received = kotlinx.coroutines.CompletableDeferred<Int>()
                scope.launch {
                    val incoming = host.acceptNext() ?: return@launch
                    val connection = incoming.accept().connect()
                    received.complete(connection.readDatagram().size)
                }

                val connection = client.connect(host.addr(), MCNET_ALPN)
                val max = connection.maxDatagramSize()
                println("iroh maxDatagramSize=$max datagramSendBufferSpace=${connection.datagramSendBufferSpace()}")

                // Conservative floor: QUIC's initial MTU leaves roughly this
                // much once headers are taken out, and any Bedrock tunnel would
                // have to size its frames against the floor rather than the
                // peak, since the peak is only reached after MTU discovery.
                assertTrue(
                    max != null && max.toLong() >= 1000L,
                    "datagram budget fell below the usable floor; got $max",
                )

                val payload = ByteArray(1000) { 7 }
                connection.sendDatagram(payload)
                assertEquals(payload.size, received.await(), "datagram should arrive intact")
            }
        } finally {
            runCatching { client.shutdown() }
            runCatching { host.shutdown() }
        }
    }
}
