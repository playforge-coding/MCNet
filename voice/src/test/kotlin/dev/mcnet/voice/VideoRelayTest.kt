package dev.mcnet.voice

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RecvStream
import computer.iroh.SendStream
import computer.iroh.presetMinimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Video through the relay, against real iroh endpoints on loopback: the
 * camera flag in the roster, forwarding only to watchers, catching a late
 * watcher up from the keyframe, and ending a group when the camera goes off.
 */
class VideoRelayTest {
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

    /** One client as the transport sees it: a connection, the control stream, and everything that arrived on it. */
    private inner class Client(
        val connection: Connection,
        private val send: SendStream,
        private val recv: RecvStream,
    ) {
        /** Every video event received, as (speaker, group, event). */
        val video = CopyOnWriteArrayList<Triple<Int, Int, VideoEvent>>()

        init {
            scope.launch {
                // As the real transport does: groups put back in order.
                val sequencer =
                    VideoStreams.Sequencer {
                        speaker,
                        group,
                        event,
                        ->
                        video += Triple(speaker, group, event)
                    }
                // Ends with an exception when the connection closes at teardown.
                runCatching {
                    while (true) {
                        val stream = connection.acceptUni()
                        launch { VideoStreams.read(stream, sequencer::accept) }
                    }
                }
            }
        }

        suspend fun roster(size: Int): List<RosterEntry> {
            while (true) {
                val message = assertNotNull(Control.read(recv), "control stream ended early")
                if (message.type == Control.ROSTER) {
                    val roster = Control.decodeRoster(message.body)
                    if (roster.size == size) return roster
                }
            }
        }

        /** Waits for a roster in which [predicate] holds. */
        suspend fun rosterWhere(predicate: (List<RosterEntry>) -> Boolean): List<RosterEntry> {
            while (true) {
                val message = assertNotNull(Control.read(recv), "control stream ended early")
                if (message.type == Control.ROSTER) {
                    val roster = Control.decodeRoster(message.body)
                    if (predicate(roster)) return roster
                }
            }
        }

        suspend fun frames(count: Int): List<VideoEvent.Frame> {
            while (video.count { it.third is VideoEvent.Frame } < count) delay(10)
            return video.map { it.third }.filterIsInstance<VideoEvent.Frame>()
        }

        suspend fun awaitEnd() {
            while (video.none { it.third is VideoEvent.End }) delay(10)
        }

        /** Sends one control message on the same stream the PING went out on, as the real transport does. */
        suspend fun control(
            type: Byte,
            body: ByteArray,
        ) = Control.write(send, type, body)
    }

    private suspend fun dial(
        from: Endpoint,
        host: Endpoint,
    ): Client {
        val connection = from.connect(host.addr(), VOICE_ALPN)
        val control = connection.openBi()
        val send = control.send()
        Control.write(send, Control.PING)
        return Client(connection, send, control.recv())
    }

    private fun frame(
        keyframe: Boolean,
        seed: Int,
    ) = VideoEvent.Frame(keyframe, ByteArray(300 + seed) { (it + seed).toByte() })

    @Test
    fun `video reaches watchers only and a late watcher is caught up from the keyframe`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            val carol = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(UUID.randomUUID(), "bob"))
            server.bind(carol.id().toString(), VoiceServer.Player(UUID.randomUUID(), "carol"))

            withTimeout(60_000) {
                val aliceClient = dial(alice, hostEndpoint)
                aliceClient.roster(1)
                val bobClient = dial(bob, hostEndpoint)
                bobClient.roster(2)
                val carolClient = dial(carol, hostEndpoint)
                val roster = carolClient.roster(3)
                val aliceId = roster.first { it.name == "alice" }.speaker

                // Alice turns her camera on; everyone sees the flag.
                aliceClient.control(Control.CAMERA, Control.encodeCamera(true))
                assertTrue(bobClient.rosterWhere { r -> r.any { it.name == "alice" && it.camera } }.isNotEmpty())

                // Bob watches from the start, Carol does not.
                bobClient.control(Control.WATCH, Control.encodeWatch(aliceId, true))
                delay(200)

                val writer = VideoStreams.Writer(aliceClient.connection, scope)
                writer.handle(0, 1, VideoEvent.Begin)
                writer.handle(0, 1, frame(true, 1))
                writer.handle(0, 1, frame(false, 2))

                val bobFrames = bobClient.frames(2)
                assertTrue(bobFrames[0].keyframe)
                assertContentEquals(frame(false, 2).data, bobFrames[1].data)
                assertEquals(aliceId, bobClient.video.first().first, "the relay must stamp the sender's id")
                assertEquals(1, bobClient.video.first().second)
                assertTrue(carolClient.video.isEmpty(), "video must not reach a participant who did not ask")

                // Carol starts watching mid-group and gets the whole group so far.
                carolClient.control(Control.WATCH, Control.encodeWatch(aliceId, true))
                val carolFrames = carolClient.frames(2)
                assertTrue(carolFrames[0].keyframe, "a late watcher must start at the keyframe")
                assertContentEquals(frame(false, 2).data, carolFrames[1].data)

                // From here on both get live frames.
                writer.handle(0, 1, frame(false, 3))
                assertContentEquals(frame(false, 3).data, bobClient.frames(3)[2].data)
                assertContentEquals(frame(false, 3).data, carolClient.frames(3)[2].data)

                // Camera off ends the group for both.
                aliceClient.control(Control.CAMERA, Control.encodeCamera(false))
                bobClient.awaitEnd()
                carolClient.awaitEnd()
                assertTrue(bobClient.rosterWhere { r -> r.none { it.camera } }.isNotEmpty())
            }
        }

    @Test
    fun `a group that ends cleanly delivers every frame before the next begins`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(UUID.randomUUID(), "bob"))

            withTimeout(60_000) {
                val aliceClient = dial(alice, hostEndpoint)
                aliceClient.roster(1)
                val bobClient = dial(bob, hostEndpoint)
                val aliceId = bobClient.roster(2).first { it.name == "alice" }.speaker
                bobClient.control(Control.WATCH, Control.encodeWatch(aliceId, true))
                delay(200)

                // What CameraSender does at every keyframe: End, then Begin.
                val writer = VideoStreams.Writer(aliceClient.connection, scope)
                for (group in 1..3) {
                    writer.handle(0, group, VideoEvent.Begin)
                    for (i in 0 until 20) writer.handle(0, group, frame(i == 0, group * 100 + i))
                    writer.handle(0, group, VideoEvent.End)
                    // Real groups are at least a second apart, which the
                    // sequencer relies on, so whole groups are not fired at
                    // once. Only this group's start is awaited, not its tail:
                    // the tail still races the next group's Begin, which is
                    // the overlap this test is about.
                    while (bobClient.video.none { it.second == group && it.third == VideoEvent.Begin }) delay(5)
                }

                val frames = bobClient.frames(60)
                assertEquals(60, frames.size, "no frame of a finished group may be lost")
                assertEquals(listOf(1, 2, 3), bobClient.video.filter { it.third is VideoEvent.Begin }.map { it.second })
            }
        }

    @Test
    fun `control messages arrive in the order they were sent`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(UUID.randomUUID(), "bob"))

            withTimeout(60_000) {
                val aliceConnection = alice.connect(hostEndpoint.addr(), VOICE_ALPN)
                val control = aliceConnection.openBi()
                Control.write(control.send(), Control.PING)
                val bobClient = dial(bob, hostEndpoint)
                bobClient.roster(2)

                // What a camera restart, or a burst of toggles, sends: the
                // last one must win however fast they go out.
                val writer = Control.Writer()
                launch { writer.run(control.send()) }
                repeat(50) { writer.send(Control.CAMERA, Control.encodeCamera(it % 2 == 1)) }
                writer.send(Control.CAMERA, Control.encodeCamera(false))
                writer.send(Control.CAMERA, Control.encodeCamera(true))

                bobClient.rosterWhere { r -> r.any { it.name == "alice" && it.camera } }
                delay(300)
                assertTrue(
                    server.roster.first { it.name == "alice" }.camera,
                    "the final message must be the one applied",
                )
                writer.close()
            }
        }

    @Test
    fun `a newer group supersedes the old one and unwatching stops delivery`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            val bob = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))
            server.bind(bob.id().toString(), VoiceServer.Player(UUID.randomUUID(), "bob"))

            withTimeout(60_000) {
                val aliceClient = dial(alice, hostEndpoint)
                aliceClient.roster(1)
                val bobClient = dial(bob, hostEndpoint)
                val aliceId = bobClient.roster(2).first { it.name == "alice" }.speaker
                bobClient.control(Control.WATCH, Control.encodeWatch(aliceId, true))
                delay(200)

                val writer = VideoStreams.Writer(aliceClient.connection, scope)
                writer.handle(0, 1, VideoEvent.Begin)
                writer.handle(0, 1, frame(true, 1))
                bobClient.frames(1)

                // Group 2 starts without group 1 having finished: Bob sees a
                // Begin for 2 and, eventually, the End of 1 from the reset.
                writer.handle(0, 2, VideoEvent.Begin)
                writer.handle(0, 2, frame(true, 9))
                val frames = bobClient.frames(2)
                assertTrue(frames[1].keyframe)
                while (bobClient.video.none { it.second == 1 && it.third is VideoEvent.End }) delay(10)
                assertTrue(bobClient.video.any { it.second == 2 && it.third is VideoEvent.Begin })

                // Unwatch: the live group ends for Bob and nothing more arrives.
                bobClient.control(Control.WATCH, Control.encodeWatch(aliceId, false))
                while (bobClient.video.none { it.second == 2 && it.third is VideoEvent.End }) delay(10)
                writer.handle(0, 2, frame(false, 10))
                writer.handle(0, 3, VideoEvent.Begin)
                writer.handle(0, 3, frame(true, 11))
                delay(300)
                assertEquals(2, bobClient.video.count { it.third is VideoEvent.Frame })
            }
        }

    @Test
    fun `the local participant sends and receives video in-process`() =
        runBlocking {
            val server = VoiceServer(scope)
            val hostEndpoint = host(server)
            val alice = bind()
            server.bind(alice.id().toString(), VoiceServer.Player(UUID.randomUUID(), "alice"))

            val seen = CopyOnWriteArrayList<Triple<Int, Int, VideoEvent>>()
            val local =
                server.attachLocal(
                    VoiceServer.Player(UUID.randomUUID(), "host"),
                    object : VoiceServer.Sink {
                        override fun deliver(datagram: ByteArray) {}

                        override fun video(
                            speaker: Int,
                            group: Int,
                            event: VideoEvent,
                        ) {
                            seen += Triple(speaker, group, event)
                        }

                        override fun roster(entries: List<RosterEntry>) {}

                        override fun closed(reason: String) {}
                    },
                )

            withTimeout(60_000) {
                val aliceClient = dial(alice, hostEndpoint)
                val aliceId = aliceClient.roster(2).first { it.name == "alice" }.speaker

                // Host to Alice: Alice watches, the host's camera turns on.
                aliceClient.control(Control.WATCH, Control.encodeWatch(local.speaker, true))
                delay(200)
                local.announceCamera(true)
                local.inboundVideo(1, VideoEvent.Begin)
                local.inboundVideo(1, frame(true, 4))
                val toAlice = aliceClient.frames(1)
                assertContentEquals(frame(true, 4).data, toAlice[0].data)
                assertEquals(local.speaker, aliceClient.video.first().first)

                // Alice to host: the host watches in-process.
                local.watch(aliceId, true)
                val writer = VideoStreams.Writer(aliceClient.connection, scope)
                writer.handle(0, 7, VideoEvent.Begin)
                writer.handle(0, 7, frame(true, 5))
                while (seen.none { it.third is VideoEvent.Frame }) delay(10)
                val toHost = seen.first { it.third is VideoEvent.Frame }
                assertEquals(aliceId, toHost.first)
                assertEquals(7, toHost.second)
                assertContentEquals(frame(true, 5).data, (toHost.third as VideoEvent.Frame).data)
            }
        }
}
