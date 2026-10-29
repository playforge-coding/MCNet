package dev.mcnet.voice

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RecvStream
import computer.iroh.presetMinimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The relay against real iroh endpoints on loopback: the roster handshake,
 * fan-out with the speaker id stamped in, admission control, and the
 * in-process path the host's own player uses.
 */
class VoiceRelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoints = mutableListOf<Endpoint>()

    @AfterTest
    fun tearDown() {
        runBlocking { endpoints.forEach { runCatching { it.shutdown() } } }
        scope.cancel()
    }

    private suspend fun bind(): Endpoint =
        Endpoint
            .bind(EndpointOptions(preset = presetMinimal(), alpns = listOf(VOICE_ALPN)))
            .also { endpoints += it }

    /** A host endpoint whose accept loop feeds every connection to [server]. */
    private suspend fun host(server: VoiceServer): Endpoint {
        val endpoint = bind()
        scope.launch {
            while (true) {
                val incoming = endpoint.acceptNext() ?: break
                launch { server.accept(incoming.accept().connect()) }
            }
        }
        return endpoint
    }

    /** Dials like the client transport does: connect, open control, send a PING. */
    private suspend fun dial(
        from: Endpoint,
        host: Endpoint,
    ): Pair<Connection, RecvStream> {
        val connection = from.connect(host.addr(), VOICE_ALPN)
        val control = connection.openBi()
        Control.write(control.send(), Control.PING)
        return connection to control.recv()
    }

    private suspend fun rosterOf(
        recv: RecvStream,
        size: Int,
    ): List<RosterEntry> {
        while (true) {
            val message = assertNotNull(Control.read(recv), "control stream ended early")
            if (message.type == Control.ROSTER) {
                val roster = Control.decodeRoster(message.body)
                if (roster.size == size) return roster
            }
        }
    }

    @Test
    fun `frames reach the other participants stamped with the sender`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(UUID.randomUUID(), "bob"))

            withTimeout(60_000) {
                val (aliceConn, aliceControl) = dial(alice, hostEndpoint)
                rosterOf(aliceControl, 1)

                val (bobConn, bobControl) = dial(bob, hostEndpoint)
                val roster = rosterOf(bobControl, 2)
                assertEquals(rosterOf(aliceControl, 2), roster, "both sides should see the same roster")
                val aliceId = roster.first { it.name == "alice" }.speaker

                val payload = ByteArray(80) { it.toByte() }
                aliceConn.sendDatagram(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, 42, payload))

                val received = assertNotNull(VoiceFrames.decode(bobConn.readDatagram()))
                assertEquals(aliceId, received.speaker, "the relay must stamp the sender's id")
                assertEquals(42, received.sequence)
                assertContentEquals(payload, received.payload)
            }
        }

    @Test
    fun `an endpoint nobody vouched for is turned away`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val stranger = bind()

            withTimeout(60_000) {
                val connection = stranger.connect(hostEndpoint.addr(), VOICE_ALPN)
                // The relay waits a few seconds for a binding, then hangs up.
                val reason = connection.closed()
                assertTrue(reason.contains("no player", ignoreCase = true), "unexpected close reason: $reason")
                assertTrue(server.isEmpty, "a refused connection must not join the roster")
            }
        }

    @Test
    fun `the local participant hears and is heard without a socket`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))

            val heard = CopyOnWriteArrayList<ByteArray>()
            val rosters = CopyOnWriteArrayList<List<RosterEntry>>()
            val local =
                server.attachLocal(
                    VoiceServer.Player(UUID.randomUUID(), "host"),
                    object : VoiceServer.Sink {
                        override fun deliver(datagram: ByteArray) {
                            heard += datagram
                        }

                        override fun video(
                            speaker: Int,
                            group: Int,
                            event: VideoEvent,
                        ) {}

                        override fun roster(entries: List<RosterEntry>) {
                            rosters += entries
                        }

                        override fun closed(reason: String) {}
                    },
                )

            withTimeout(60_000) {
                val (aliceConn, aliceControl) = dial(alice, hostEndpoint)
                val roster = rosterOf(aliceControl, 2)
                assertTrue(rosters.last().size == 2, "the local participant gets roster updates too")

                // Host to Alice.
                local.inbound(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, 1, byteArrayOf(5, 6, 7)))
                val toAlice = assertNotNull(VoiceFrames.decode(aliceConn.readDatagram()))
                assertEquals(local.speaker, toAlice.speaker)

                // Alice to host.
                aliceConn.sendDatagram(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, 2, byteArrayOf(9)))
                while (heard.isEmpty()) kotlinx.coroutines.delay(10)
                val toHost = assertNotNull(VoiceFrames.decode(heard.first()))
                assertEquals(roster.first { it.name == "alice" }.speaker, toHost.speaker)

                server.detach(local)
                assertEquals(1, rosterOf(aliceControl, 1).size)
            }
        }
}
