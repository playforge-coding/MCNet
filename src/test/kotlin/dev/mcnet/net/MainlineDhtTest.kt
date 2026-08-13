package dev.mcnet.net

import computer.iroh.EndpointBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the contract between MCNet and the DHT-capable iroh bindings.
 *
 * The assertions deliberately branch on [MainlineDht.isAvailable] so this test
 * is meaningful under both builds:
 *
 *  - against the published `computer.iroh:iroh`, it proves MCNet degrades
 *    gracefully instead of crashing when `addressLookup=dht` is configured;
 *  - against bindings built from `dht/apply.sh` (run the suite with
 *    `-Pmcnet.irohJar=...`), it proves the reflective hookup matches the
 *    generated signatures and that an endpoint really binds with a Mainline
 *    DHT address lookup attached.
 */
class MainlineDhtTest {

    @Test
    fun `dht support is either absent or usable, never half-wired`() = runBlocking {
        val builder = EndpointBuilder()
        builder.applyMinimal()
        builder.alpns(listOf(MCNET_ALPN))

        val attached = MainlineDht.attach(builder, publish = false)

        if (!MainlineDht.isAvailable) {
            assertFalse(attached, "attach must report failure when the class is missing")
            println("iroh bindings have no DHT support; verified the graceful fallback path")
            return@runBlocking
        }

        assertTrue(attached, "DhtAddressLookup is on the classpath, so attaching must succeed")

        // Binding is where iroh calls into_address_lookup, which is what
        // actually constructs the DHT node and registers its socket with the
        // Tokio reactor. If the patch were wrong, this is where it would show.
        val endpoint = withTimeout(60_000) { builder.bind() }
        try {
            assertTrue(endpoint.id().toString().isNotEmpty())
            println("bound an endpoint with Mainline DHT lookup: ${endpoint.id()}")
        } finally {
            runCatching { endpoint.shutdown() }
        }
    }

    @Test
    fun `a lookup cannot be reused across endpoints`() = runBlocking {
        if (!MainlineDht.isAvailable) return@runBlocking

        // The Rust side takes the builder out of its mutex, so a second attach
        // of the same object must fail rather than silently produce an endpoint
        // with no address lookup.
        val cls = Class.forName("computer.iroh.DhtAddressLookup")
        val lookup = cls
            .getConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance(false, false)
        val method = EndpointBuilder::class.java.getMethod("addressLookup", cls)

        val first = EndpointBuilder().apply { applyMinimal() }
        method.invoke(first, lookup)

        val second = EndpointBuilder().apply { applyMinimal() }
        val reused = runCatching { method.invoke(second, lookup) }
        assertTrue(reused.isFailure, "reusing a consumed DhtAddressLookup must fail loudly")
    }
}
