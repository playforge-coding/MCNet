package dev.mcnet.voice.client.video

import dev.mcnet.video.ffi.CameraInfo
import dev.mcnet.video.ffi.listCameras
import dev.mcnet.video.ffi.uniffiEnsureInitialized
import dev.mcnet.voice.VoiceLog

/**
 * Whether the native video library (capture and AV1 codecs) is usable on
 * this machine. The jar ships it for the platforms CI builds; anywhere
 * else, voice works and video is simply absent.
 */
object VideoNative {
    /** Why the library could not be loaded, or null if it could. */
    @Volatile var unavailableReason: String? = null
        private set

    val available: Boolean by lazy {
        try {
            // Loads the library through JNA and checks that these bindings
            // were generated from this very build of it.
            uniffiEnsureInitialized()
            VoiceLog.logger.info("video library loaded")
            true
        } catch (t: Throwable) {
            unavailableReason = t.message?.lineSequence()?.first() ?: t::class.simpleName
            VoiceLog.logger.info("video is not available on this platform: {}", unavailableReason)
            false
        }
    }

    /** Cameras the OS reports, or none if video is unavailable or enumeration failed. */
    fun cameras(): List<CameraInfo> =
        if (!available) {
            emptyList()
        } else {
            runCatching { listCameras() }
                .onFailure { VoiceLog.logger.warn("could not list cameras", it) }
                .getOrDefault(emptyList())
        }
}
