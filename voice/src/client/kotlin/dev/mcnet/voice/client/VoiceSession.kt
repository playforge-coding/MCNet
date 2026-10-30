package dev.mcnet.voice.client

import computer.iroh.EndpointAddr
import dev.mcnet.net.IrohRuntime
import dev.mcnet.voice.RosterEntry
import dev.mcnet.voice.VideoEvent
import dev.mcnet.voice.VoiceFrames
import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.VoiceServer
import dev.mcnet.voice.client.audio.Microphone
import dev.mcnet.voice.client.audio.Placement
import dev.mcnet.voice.client.audio.Playback
import dev.mcnet.voice.client.video.CameraSender
import dev.mcnet.voice.client.video.HeadCameras
import dev.mcnet.voice.client.video.VideoNative
import dev.mcnet.voice.client.video.VideoReceivers
import dev.mcnet.voice.client.video.VideoSurface
import kotlinx.coroutines.launch
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One call, from the player's point of view: a transport to the relay, a
 * microphone and camera feeding it, playback and decoders draining it.
 *
 * The session is the only thing the UI talks to. It owns the media threads
 * and restarts them when the player picks a different device.
 */
class VoiceSession private constructor(
    private val me: UUID,
) : TransportListener,
    Microphone.Sink,
    CameraSender.Out {
    enum class State { CONNECTING, LIVE, FAILED, CLOSED }

    @Volatile var state: State = State.CONNECTING
        private set

    @Volatile var error: String? = null
        private set

    /** Everyone in the call, as the host last announced it. Includes this player. */
    @Volatile var roster: List<RosterEntry> = emptyList()
        private set

    /**
     * How far voices carry on this host, in blocks. Zero means they do not
     * fade at all; null means the host has not said yet.
     */
    @Volatile var range: Float? = null
        private set

    /** Where voices come from as of the last frame, for [Playback]. */
    @Volatile private var placement: Placement = Placement.Pending

    /**
     * Render thread only. Where each participant was last seen. A speaker can
     * be in voice range but beyond the distance the server tracks players to
     * this client at; keeping their last position fades them out from where
     * they left rather than snapping them into the listener's head.
     */
    private val lastSeen = HashMap<UUID, Vec3>()
    private var lastSeenLevel: ClientLevel? = null

    /**
     * Players close enough for their camera to be drawn above their head,
     * measured from the viewer's camera to their eyes. The one place that
     * decides it: [HeadCameras] draws exactly these, and with cameras above
     * heads only, exactly these are fetched. Keyed by player rather than by
     * speaker id, so an id the host hands to someone new carries nothing
     * over.
     */
    @Volatile var near: Set<UUID> = emptySet()
        private set

    @Volatile var microphone: Microphone? = null
        private set

    /** This player's camera, while it is on. Never on at the start of a session. */
    @Volatile var camera: CameraSender? = null
        private set

    /** Why the camera last turned itself off, if it did; cleared when it is turned on again. */
    @Volatile var cameraError: String? = null
        private set

    /** The newest sender, which a replacement waits for in case it is still releasing the device. */
    private var latestCamera: CameraSender? = null

    /** Group numbers for this player's video, across camera restarts; see [CameraSender]. */
    private val videoGroups = AtomicInteger()

    /** The other participants' cameras, decoded. */
    val receivers = VideoReceivers()

    /** This player's own camera as it is being sent, for the HUD. */
    val preview = VideoSurface("self")

    private var playback: Playback? = null
    private var transport: VoiceTransport? = null

    @Volatile private var speakerToPlayer: Map<Int, UUID> = emptyMap()
    private val lastHeard = ConcurrentHashMap<Int, Long>()

    /** Speakers the host has been asked to forward video from. */
    private val watched = HashSet<Int>()

    val isTransmitting: Boolean get() = microphone?.transmitting == true

    /** Participants other than this player. */
    val others: List<RosterEntry> get() = roster.filter { it.uuid != me }

    /** Who has sent audio in the last quarter second. */
    fun speaking(): List<RosterEntry> {
        val now = System.currentTimeMillis()
        return roster.filter { now - (lastHeard[it.speaker] ?: 0L) < SPEAKING_WINDOW_MS }
    }

    fun isSpeaking(uuid: UUID): Boolean = speaking().any { it.uuid == uuid }

    /**
     * Render thread, once a frame. Records where this player's head and
     * every other participant are, and which way the camera faces, so the
     * audio thread can place their voices, and which participants are near
     * enough for their camera to be drawn in the world. The game already
     * tracks nearby players, so no positions travel over voice.
     *
     * When neither is needed (no proximity, and no cameras above heads),
     * it does no lookups at all.
     */
    fun updateSpatial(
        camera: Camera,
        level: ClientLevel,
        partialTick: Float,
    ) {
        val live = state == State.LIVE
        val range = range
        val positional = live && range != null && range > 0f
        val wantsNear =
            live && VoiceClientConfig.showCameras && VoiceClientConfig.cameraPlacement.aboveHead &&
                VideoNative.available
        if (!positional) placement = if (live && range != null) Placement.Flat else Placement.Pending
        if (!positional && !wantsNear) {
            setNear(emptySet())
            return
        }
        val listener = level.getPlayerByUUID(me)
        if (listener == null) {
            if (positional) placement = Placement.Pending
            return
        }
        // Positions from another dimension mean nothing here.
        if (level !== lastSeenLevel) {
            lastSeen.clear()
            lastSeenLevel = level
        }
        val speakers = speakerToPlayer
        lastSeen.keys.retainAll(speakers.values.toSet())
        val viewer = camera.position()
        val wasNear = near
        val nowNear = HashSet<UUID>()
        for (uuid in speakers.values) {
            if (uuid == me) continue
            val player = level.getPlayerByUUID(uuid) ?: continue
            val position = player.getEyePosition(partialTick)
            lastSeen[uuid] = position
            if (wantsNear) {
                // Someone pacing around the edge should not have their camera
                // fetched and dropped over and over, so leaving takes further
                // than arriving.
                val limit = if (uuid in wasNear) NEAR_LEAVE else HeadCameras.DRAW_RANGE
                if (position.distanceToSqr(viewer) <= limit * limit) nowNear += uuid
            }
        }
        if (positional) {
            val placed = HashMap<Int, Vec3>()
            speakers.forEach { (speaker, uuid) -> lastSeen[uuid]?.let { placed[speaker] = it } }
            // The camera reuses its vectors; copy them before handing them to another thread.
            placement =
                Placement.Positional(
                    range,
                    Vector3f(camera.forwardVector()),
                    Vector3f(camera.upVector()),
                    listener.getEyePosition(partialTick),
                    placed,
                )
        }
        setNear(nowNear)
    }

    private fun setNear(players: Set<UUID>) {
        if (players == near) return
        near = players
        refreshWatches()
    }

    /** Tears the audio down and brings it back on the configured devices. */
    fun restartAudio() {
        if (state != State.LIVE) return
        stopAudio()
        startAudio()
    }

    /**
     * Turns this player's camera on or off. Off is also what happens when
     * the session ends. The host hears that the camera is on only once it
     * has actually opened, so nobody watches a camera that never starts.
     */
    @Synchronized
    fun setCamera(on: Boolean) {
        if (on == (camera != null)) return
        if (on) {
            if (state != State.LIVE || !VideoNative.available) return
            cameraError = null
            val sender = CameraSender(this, preview, videoGroups, latestCamera)
            camera = sender
            latestCamera = sender
            sender.start()
        } else {
            camera?.stop()
            camera = null
            transport?.announceCamera(false)
        }
    }

    /** Reopens the camera on the configured device and quality, if it is on. */
    @Synchronized
    fun restartCamera() {
        if (camera == null) return
        setCamera(false)
        setCamera(true)
    }

    /**
     * Tells the host which cameras to forward, from the roster, the player's
     * settings and, with cameras above heads only, who is near enough to be
     * drawn. Nothing is forwarded until asked for, so a client that cannot
     * decode, or has cameras hidden, or is alone in the wilderness, costs
     * the host nothing.
     *
     * Called from the render thread and from network threads alike, so the
     * wanted set is worked out under the same lock that applies it. Worked
     * out before taking the lock, a refresh that read the old roster or the
     * old [near] set could apply last and stick.
     */
    fun refreshWatches() {
        if (state != State.LIVE) return
        synchronized(watched) {
            val wanted =
                if (VoiceClientConfig.showCameras && VideoNative.available) {
                    val everyone = VoiceClientConfig.cameraPlacement.onHud
                    val near = near
                    roster
                        .filter { it.camera && it.uuid != me && !VoiceClientConfig.isCameraHidden(it.uuid) }
                        .filter { everyone || it.uuid in near }
                        .map { it.speaker }
                        .toSet()
                } else {
                    emptySet()
                }
            // The decoder hears first. The host's own player is attached
            // in-process, where asking to watch replays the current group
            // synchronously, straight into the decoder's queue; told second,
            // the decoder would throw that replay away as unwanted. This
            // also releases the decoders of anyone no longer wanted, and
            // makes sure frames still in flight for them are ignored.
            receivers.watch(wanted)
            (wanted - watched).forEach { transport?.watch(it, true) }
            (watched - wanted).forEach { transport?.watch(it, false) }
            watched.clear()
            watched += wanted
        }
    }

    fun close() {
        state = State.CLOSED
        stopAudio()
        stopVideo()
        transport?.close()
        transport = null
    }

    private fun startAudio() {
        val gainFor = { speaker: Int ->
            val player = speakerToPlayer[speaker]
            val perPlayer = if (player == null) 1.0 else VoiceClientConfig.volumeOf(player)
            (perPlayer * VoiceClientConfig.volume).toFloat()
        }
        playback = Playback(VoiceClientConfig.speaker, gainFor) { placement }.start()
        microphone = Microphone(VoiceClientConfig.microphone, this).start()
    }

    private fun stopAudio() {
        microphone?.stop()
        microphone = null
        playback?.stop()
        playback = null
    }

    private fun stopVideo() {
        synchronized(this) {
            camera?.stop()
            camera = null
        }
        receivers.close()
        preview.close()
    }

    // Transport callbacks, on network threads.

    override fun onDatagram(datagram: ByteArray) {
        val frame = VoiceFrames.decode(datagram) ?: return
        if (frame.type == VoiceFrames.TYPE_AUDIO) lastHeard[frame.speaker] = System.currentTimeMillis()
        val player = speakerToPlayer[frame.speaker]
        if (player != null && VoiceClientConfig.isMuted(player)) return
        playback?.enqueue(frame)
    }

    override fun onVideo(
        speaker: Int,
        group: Int,
        event: VideoEvent,
    ) = receivers.onVideo(speaker, group, event)

    override fun onSettings(range: Float) {
        this.range = range
        // A positive range waits for the next frame to place anyone.
        placement = if (range <= 0f) Placement.Flat else Placement.Pending
    }

    override fun onRoster(entries: List<RosterEntry>) {
        val previous = speakerToPlayer.keys
        roster = entries
        speakerToPlayer = entries.associate { it.speaker to it.uuid }
        (previous - speakerToPlayer.keys).forEach { gone ->
            playback?.retire(gone)
            lastHeard.remove(gone)
        }
        // Drops the decoders of anyone who left, along with any watch on them.
        refreshWatches()
    }

    override fun onClosed(reason: String) {
        VoiceLog.logger.info("voice session ended: {}", reason)
        error = reason
        state = State.FAILED
        stopAudio()
        stopVideo()
    }

    // Microphone callbacks, on the capture thread.

    override fun frame(
        opus: ByteArray,
        sequence: Int,
    ) {
        transport?.send(VoiceFrames.encode(VoiceFrames.TYPE_AUDIO, 0, sequence, opus))
    }

    override fun endOfBurst(sequence: Int) {
        transport?.send(VoiceFrames.encode(VoiceFrames.TYPE_END, 0, sequence, ByteArray(0)))
    }

    // Camera callbacks, on the camera thread.

    override fun video(
        group: Int,
        event: VideoEvent,
    ): Boolean = transport?.sendVideo(group, event) ?: false

    override fun started(sender: CameraSender) {
        synchronized(this) {
            if (camera === sender) transport?.announceCamera(true)
        }
    }

    override fun stopped(
        sender: CameraSender,
        error: String?,
    ) {
        synchronized(this) {
            // A sender stopped on purpose has already been replaced or
            // cleared; only one that died on its own is still current.
            if (camera !== sender) return
            camera = null
            cameraError = error
            transport?.announceCamera(false)
        }
    }

    companion object {
        private const val SPEAKING_WINDOW_MS = 250L

        /** A speaker counts as near until this far away, once they have come within [HeadCameras.DRAW_RANGE]. */
        private const val NEAR_LEAVE = HeadCameras.DRAW_RANGE + 8.0

        /** Dials [host] in the background; the returned session reports progress through [state]. */
        fun startRemote(
            me: UUID,
            host: EndpointAddr,
        ): VoiceSession {
            val session = VoiceSession(me)
            val transport = RemoteTransport(host, session)
            session.transport = transport
            IrohRuntime.scope.launch {
                try {
                    transport.connect()
                    if (session.state == State.CONNECTING) {
                        session.state = State.LIVE
                        session.startAudio()
                        session.refreshWatches()
                        VoiceLog.logger.info("voice connected to {}", host.id().fmtShort())
                    }
                } catch (e: Exception) {
                    VoiceLog.logger.warn("voice connection failed", e)
                    session.error = e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "error")
                    session.state = State.FAILED
                }
            }
            return session
        }

        /** Joins the relay running in this process, for the player who is hosting. */
        fun startLocal(
            server: VoiceServer,
            player: VoiceServer.Player,
        ): VoiceSession {
            val session = VoiceSession(player.uuid)
            session.transport = LocalTransport(server, player, session)
            session.state = State.LIVE
            session.startAudio()
            session.refreshWatches()
            VoiceLog.logger.info("voice attached to the local relay")
            return session
        }
    }
}
