package dev.mcnet.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry addons hang their ALPNs on. Small, but the lookup is by byte
 * content rather than identity, and getting that wrong would silently route
 * every addon connection to the "unknown protocol" branch.
 */
class ProtocolsTest {
    @Test
    fun `mcnet's own alpns are always advertised first`() {
        val alpns = Protocols.alpns()
        assertContentEquals(MCNET_ALPN, alpns[0])
        assertContentEquals(MCNET_PROBE_ALPN, alpns[1])
    }

    @Test
    fun `a registered alpn is advertised and found by content`() {
        val alpn = "mcnet-test-${System.nanoTime()}/1".toByteArray()
        val handler = ProtocolHandler { }
        Protocols.register(alpn, handler)

        assertTrue(Protocols.alpns().any { it.contentEquals(alpn) }, "registered ALPN should be advertised")
        // A fresh array with the same bytes, as the handshake hands back.
        assertNotNull(Protocols.handlerFor(alpn.copyOf()), "lookup must compare content, not identity")
        assertNull(Protocols.handlerFor("nope/1".toByteArray()))
    }

    @Test
    fun `reserved and duplicate alpns are refused`() {
        assertFailsWith<IllegalArgumentException> { Protocols.register(MCNET_ALPN) { } }
        assertFailsWith<IllegalArgumentException> { Protocols.register(MCNET_PROBE_ALPN) { } }

        val alpn = "mcnet-dup-${System.nanoTime()}/1".toByteArray()
        Protocols.register(alpn) { }
        assertFailsWith<IllegalStateException> { Protocols.register(alpn.copyOf()) { } }
    }
}
