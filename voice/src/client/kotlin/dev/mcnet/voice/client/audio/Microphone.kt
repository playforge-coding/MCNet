package dev.mcnet.voice.client.audio

import dev.mcnet.voice.FRAME_SAMPLES
import dev.mcnet.voice.Opus
import dev.mcnet.voice.SAMPLE_RATE
import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.client.VoiceClientConfig
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.ALC11
import org.lwjgl.system.MemoryUtil
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captures the microphone, decides whether the player is talking, and hands
 * encoded frames to [sink].
 *
 * Capture does not need an OpenAL context, only a capture device, so this
 * thread never touches Minecraft's audio state. With a null [sink] it runs as
 * a level meter only, which is what the settings screen uses to let the
 * player tune the activation threshold.
 */
class Microphone(
    private val deviceName: String,
    private val sink: Sink?,
) {
    interface Sink {
        fun frame(
            opus: ByteArray,
            sequence: Int,
        )

        fun endOfBurst(sequence: Int)
    }

    /** Current input level in dBFS, for the meter. Silence is about -100. */
    @Volatile var level: Double = SILENCE_DB
        private set

    /** True while frames are being sent. */
    @Volatile var transmitting: Boolean = false
        private set

    /** Set each tick from the push-to-talk key. */
    @Volatile var pttDown: Boolean = false

    /** Why the microphone could not start, if it could not. */
    @Volatile var error: String? = null
        private set

    @Volatile private var running = true

    private val thread = Thread(::run, "mcnet-voice-in").apply { isDaemon = true }

    fun start(): Microphone = apply { thread.start() }

    fun stop() {
        running = false
    }

    private fun run() {
        val name: CharSequence? = deviceName.ifBlank { null }
        val device = ALC11.alcCaptureOpenDevice(name, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, SAMPLE_RATE / 2)
        if (device == MemoryUtil.NULL) {
            error = if (name == null) "no microphone found" else "could not open microphone \"$deviceName\""
            VoiceLog.logger.warn(error)
            return
        }

        val encoder =
            try {
                Opus.encoder()
            } catch (e: Exception) {
                error = "Opus failed to load: ${e.message}"
                VoiceLog.logger.error("could not create the Opus encoder", e)
                ALC11.alcCaptureCloseDevice(device)
                return
            }

        val buffer = BufferUtils.createShortBuffer(FRAME_SAMPLES)
        val pcm = ShortArray(FRAME_SAMPLES)
        var sequence = 0
        var hangover = 0
        var wasTalking = false

        try {
            ALC11.alcCaptureStart(device)
            while (running) {
                if (ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES) < FRAME_SAMPLES) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                buffer.clear()
                ALC11.alcCaptureSamples(device, buffer, FRAME_SAMPLES)
                buffer.get(0, pcm)

                applyGain(pcm, VoiceClientConfig.microphoneGain)
                val currentLevel = dbfs(pcm)
                level = currentLevel

                val talking =
                    when {
                        VoiceClientConfig.micMuted -> {
                            false
                        }

                        VoiceClientConfig.activation == VoiceClientConfig.Activation.PUSH_TO_TALK -> {
                            pttDown
                        }

                        else -> {
                            if (currentLevel >
                                VoiceClientConfig.thresholdDb
                            ) {
                                hangover = HANGOVER_FRAMES
                            } else if (hangover >
                                0
                            ) {
                                hangover--
                            }
                            hangover > 0
                        }
                    }

                if (talking) {
                    sink?.frame(encoder.encode(pcm), sequence++)
                    wasTalking = true
                } else if (wasTalking) {
                    sink?.endOfBurst(sequence++)
                    wasTalking = false
                    // A fresh burst should not carry the tail of the old one.
                    encoder.resetState()
                }
                transmitting = talking && sink != null
            }
        } catch (e: Exception) {
            if (running) VoiceLog.logger.warn("microphone thread stopped", e)
        } finally {
            transmitting = false
            level = SILENCE_DB
            runCatching { ALC11.alcCaptureStop(device) }
            runCatching { ALC11.alcCaptureCloseDevice(device) }
            runCatching { encoder.close() }
        }
    }

    private fun applyGain(
        pcm: ShortArray,
        gain: Double,
    ) {
        if (gain == 1.0) return
        for (i in pcm.indices) {
            pcm[i] = (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun dbfs(pcm: ShortArray): Double {
        var sum = 0.0
        for (sample in pcm) sum += sample.toDouble() * sample
        val rms = sqrt(sum / pcm.size)
        if (rms < 1.0) return SILENCE_DB
        return 20 * log10(rms / Short.MAX_VALUE)
    }

    private companion object {
        const val POLL_MS = 5L
        const val SILENCE_DB = -100.0

        /** How long the mic stays open after the level drops: 300 ms. */
        const val HANGOVER_FRAMES = 15
    }
}
