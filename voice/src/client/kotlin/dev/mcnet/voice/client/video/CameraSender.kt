package dev.mcnet.voice.client.video

import dev.mcnet.video.ffi.Camera
import dev.mcnet.video.ffi.Encoder
import dev.mcnet.video.ffi.EncoderConfig
import dev.mcnet.video.ffi.cameraPermission
import dev.mcnet.video.ffi.listCameras
import dev.mcnet.video.ffi.requestCameraPermission
import dev.mcnet.voice.VideoEvent
import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.client.VoiceClientConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures the webcam, encodes it to AV1, and hands the groups of pictures
 * to [out]. One thread does all of it; the camera's own thread converts
 * frames and the codec is single-call, so nothing here blocks for long.
 *
 * Bitrate adapts the simple way: when the transport refuses a frame, the
 * rest of that group is dropped, the rate comes down by a quarter and a new
 * group starts, each at most once a second so a stalled link does not turn
 * the stream into nothing but keyframes. A few quiet seconds earn a step
 * back towards the configured target.
 *
 * [previous] is the sender this one replaces, if it may still be shutting
 * down. It is waited for before the camera is opened, because most drivers
 * refuse a second open of the same device. [groups] numbers groups across
 * every sender of a session, so a replacement never reuses a number the
 * receivers have already seen.
 */
class CameraSender(
    private val out: Out,
    private val preview: VideoSurface,
    private val groups: AtomicInteger,
    private val previous: CameraSender?,
) {
    interface Out {
        /** False if a frame was not taken, which drops the rest of its group. */
        fun video(
            group: Int,
            event: VideoEvent,
        ): Boolean

        /** The camera is open and video is about to flow. */
        fun started(sender: CameraSender)

        /** The thread is done, with the reason if it failed. Always called, exactly once. */
        fun stopped(
            sender: CameraSender,
            error: String?,
        )
    }

    /** What is being sent, once the camera is open: "640x360". */
    @Volatile var description: String = ""
        private set

    /** The current encoder target in kbps, after adaptation. */
    @Volatile var kbps: Int = 0
        private set

    @Volatile private var running = true

    private val thread = Thread(::run, "mcnet-video-cam").apply { isDaemon = true }

    fun start(): CameraSender = apply { thread.start() }

    /** Asks the thread to finish. Returns at once; the camera is released shortly after. */
    fun stop() {
        running = false
    }

    private fun run() {
        var error: String? = null
        try {
            previous?.thread?.join()
            if (!running) return
            if (!cameraPermission() && !requestCameraPermission(PERMISSION_WAIT_MS)) {
                error = "camera access was not granted"
                VoiceLog.logger.warn("camera not started: {}", error)
                return
            }
            val cameras = listCameras()
            if (cameras.isEmpty()) {
                error = "no camera found"
                VoiceLog.logger.warn("camera not started: {}", error)
                return
            }
            val chosen = cameras.firstOrNull { it.id == VoiceClientConfig.camera } ?: cameras.first()
            val quality = VoiceClientConfig.cameraQuality
            val camera = Camera.open(chosen.id, quality.height.toUInt(), FPS.toUInt())
            try {
                val width = camera.width().toInt()
                val height = camera.height().toInt()
                description = "${width}x$height"
                VoiceLog.logger.info("camera {} open: {} native, sending {}", chosen.name, camera.source(), description)
                val encoder =
                    Encoder(
                        EncoderConfig(
                            width = width.toUInt(),
                            height = height.toUInt(),
                            fps = FPS.toUInt(),
                            bitrateKbps = quality.kbps.toUInt(),
                            keyframeInterval = GROUP_FRAMES.toUInt(),
                            speed = SPEED.toUInt(),
                            threads = THREADS.toUInt(),
                        ),
                    )
                try {
                    out.started(this)
                    loop(camera, encoder, quality.kbps)
                } finally {
                    encoder.close()
                }
            } finally {
                camera.stop()
                camera.close()
            }
        } catch (e: Throwable) {
            if (running) {
                error = e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "camera error")
                VoiceLog.logger.warn("camera stopped: {}", error, e)
            }
        } finally {
            out.stopped(this, error)
        }
    }

    private fun loop(
        camera: Camera,
        encoder: Encoder,
        target: Int,
    ) {
        var group = 0
        var groupOpen = false
        var forceKeyframe = true

        // Set when a frame is refused: nothing more of this group is sent,
        // and a new group is wanted as soon as the spacing below allows.
        var dropping = false
        var wantKeyframe = false
        var lastForced = 0L
        var lastCut = 0L

        var current = target
        var lastChange = System.currentTimeMillis()
        kbps = current

        while (running) {
            val frame = camera.capture(CAPTURE_WAIT_MS) ?: continue
            val now = System.currentTimeMillis()
            if (wantKeyframe && now - lastForced >= MIN_FORCED_SPACING_MS) {
                forceKeyframe = true
                wantKeyframe = false
            }
            if (forceKeyframe) lastForced = now
            frame.use {
                // What the others see, for the HUD. Cheap next to the encode.
                preview.present(frame.width().toInt(), frame.height().toInt()) { address, capacity ->
                    frame.writeRgba(address.toULong(), capacity.toULong())
                }
                // Every frame is still encoded while dropping, so the encoder's
                // references stay in step with what it will predict from.
                val packets = encoder.encode(frame, forceKeyframe)
                forceKeyframe = false
                for (packet in packets) {
                    if (packet.keyframe) {
                        // End the old group first, so its stream finishes
                        // cleanly instead of being reset by the new Begin.
                        if (groupOpen) out.video(group, VideoEvent.End)
                        group = groups.incrementAndGet()
                        groupOpen = true
                        dropping = false
                        wantKeyframe = false
                        out.video(group, VideoEvent.Begin)
                    }
                    if (!groupOpen || dropping) continue
                    if (!out.video(group, VideoEvent.Frame(packet.keyframe, packet.data))) {
                        // The link is behind. The rest of this group cannot
                        // be decoded without this frame, so stop sending it,
                        // ask for less, and start over at a keyframe.
                        dropping = true
                        wantKeyframe = true
                        if (now - lastCut >= MIN_CUT_SPACING_MS) {
                            current = maxOf(MIN_KBPS, current * 3 / 4)
                            encoder.setBitrate(current.toUInt())
                            kbps = current
                            lastCut = now
                        }
                        lastChange = now
                    }
                }
            }
            if (current < target && now - lastChange > RAISE_INTERVAL_MS) {
                current = minOf(target, current + target / 8)
                encoder.setBitrate(current.toUInt())
                lastChange = now
                kbps = current
            }
        }
        if (groupOpen) out.video(group, VideoEvent.End)
    }

    companion object {
        const val FPS = 30

        /** Three seconds between keyframes. Late joiners are caught up by the relay, not by keyframes. */
        const val GROUP_FRAMES = 90

        /** libaom realtime speed; 10 is the fastest that still looks like video. */
        const val SPEED = 10
        const val THREADS = 2
        const val MIN_KBPS = 100
        const val RAISE_INTERVAL_MS = 5_000L
        const val CAPTURE_WAIT_MS = 500uL

        /** At most one keyframe forced, and one bitrate cut, per this much time. */
        const val MIN_FORCED_SPACING_MS = 1_000L
        const val MIN_CUT_SPACING_MS = 1_000L

        /** How long the macOS permission prompt may stay open. */
        const val PERMISSION_WAIT_MS = 120_000uL
    }
}
