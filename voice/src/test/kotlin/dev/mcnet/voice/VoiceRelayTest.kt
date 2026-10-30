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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relay against real iroh endpoints on loopback: the roster handshake,
 * fan-out with the speaker id stamped in, proximity, admission control, and
 * the in-process path the host's own player uses.
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

    /** The next SETTINGS message, skipping anything else. */
    private suspend fun settingsOf(recv: RecvStream): Float {
        while (true) {
            val message = assertNotNull(Control.read(recv), "control stream ended early")
            if (message.type == Control.SETTINGS) return assertNotNull(Control.decodeSettings(message.body))
        }
    }

    @Test
    fun `voices carry only to players in range and in the same dimension`() =
        runBlocking {
            val positions = ConcurrentHashMap<UUID, VoiceServer.Position>()
            val server = VoiceServer(scope) { positions[it] }
            server.range = 48.0
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            val carol = bind()
            val aliceUuid = UUID.randomUUID()
            val bobUuid = UUID.randomUUID()
            val carolUuid = UUID.randomUUID()
            server.bind(alice.id().toString(), VoiceServer.Player(aliceUuid, "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(bobUuid, "bob"))
            server.bind(carol.id().toString(), VoiceServer.Player(carolUuid, "carol"))
            positions[aliceUuid] = VoiceServer.Position("overworld", 0.0, 64.0, 0.0)
            positions[bobUuid] = VoiceServer.Position("overworld", 10.0, 64.0, 0.0)
            positions[carolUuid] = VoiceServer.Position("overworld", 100.0, 64.0, 0.0)

            withTimeout(60_000) {
                val (aliceConn, aliceControl) = dial(alice, hostEndpoint)
                assertEquals(48f, settingsOf(aliceControl), "the range is announced before the first roster")
                rosterOf(aliceControl, 1)
                val (bobConn, bobControl) = dial(bob, hostEndpoint)
                rosterOf(bobControl, 2)
                val (carolConn, carolControl) = dial(carol, hostEndpoint)
                rosterOf(carolControl, 3)
                rosterOf(aliceControl, 3)
                rosterOf(bobControl, 3)

                suspend fun aliceSays(sequence: Int) =
                    aliceConn.sendDatagram(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, sequence, byteArrayOf(1)))

                suspend fun heardBy(connection: Connection) =
                    assertNotNull(VoiceFrames.decode(connection.readDatagram())).sequence

                suspend fun silentFor(connection: Connection) = withTimeoutOrNull(500) { connection.readDatagram() }

                aliceSays(1)
                assertEquals(1, heardBy(bobConn), "bob is 10 blocks away")
                assertNull(silentFor(carolConn), "carol is 100 blocks away")

                // Close by, but in another dimension.
                positions[carolUuid] = VoiceServer.Position("the_nether", 5.0, 64.0, 0.0)
                aliceSays(2)
                assertEquals(2, heardBy(bobConn))
                assertNull(silentFor(carolConn), "carol is in the nether")

                positions[carolUuid] = VoiceServer.Position("overworld", 40.0, 64.0, 0.0)
                aliceSays(3)
                assertEquals(3, heardBy(bobConn))
                assertEquals(3, heardBy(carolConn), "carol walked into range")

                // Turning proximity off reaches everyone, and says so.
                server.range = 0.0
                assertEquals(0f, settingsOf(carolControl))
                positions[carolUuid] = VoiceServer.Position("the_end", 1000.0, 64.0, 0.0)
                aliceSays(4)
                assertEquals(4, heardBy(carolConn))
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

                        override fun settings(range: Float) {}

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
