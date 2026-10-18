package dev.mcnet.voice.client.audio

import de.maxhenkel.opus4j.OpusDecoder
import dev.mcnet.voice.FRAME_SAMPLES
import dev.mcnet.voice.Opus
import dev.mcnet.voice.SAMPLE_RATE
import dev.mcnet.voice.VoiceFrames
import dev.mcnet.voice.VoiceLog
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.EXTThreadLocalContext
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Plays every incoming voice through OpenAL, one source per speaker.
 *
 * It opens its own device and context and binds them to this thread with
 * `ALC_EXT_thread_local_context`, so Minecraft's sound engine, which owns the
 * process-wide current context, never notices. With no proximity the sources
 * are listener-relative at the origin, which is to say plain mono.
 *
 * Mixing is OpenAL's job. This class only decodes and keeps each source fed.
 */
class Playback(
    private val deviceName: String,
    private val gainFor: (speaker: Int) -> Float,
) {
    private val queue = ConcurrentLinkedQueue<VoiceFrames.Frame>()
    private val retire = ConcurrentLinkedQueue<Int>()

    @Volatile var error: String? = null
        private set

    @Volatile private var running = true

    private val thread = Thread(::run, "mcnet-voice-out").apply { isDaemon = true }

    fun start(): Playback = apply { thread.start() }

    fun stop() {
        running = false
    }

    /** Called from the network thread; frames are consumed on the audio thread. */
    fun enqueue(frame: VoiceFrames.Frame) {
        queue.add(frame)
    }

    /** Frees the source for a speaker who left. */
    fun retire(speaker: Int) {
        retire.add(speaker)
    }

    private fun run() {
        val device = ALC10.alcOpenDevice(deviceName.ifBlank { null } as CharSequence?)
        if (device == MemoryUtil.NULL) {
            fail(if (deviceName.isBlank()) "no audio output device" else "could not open output \"$deviceName\"")
            return
        }
        val alcCaps = ALC.createCapabilities(device)
        if (!alcCaps.ALC_EXT_thread_local_context) {
            // Without it we would have to steal the current context from
            // Minecraft, which would silence the game. Refuse instead.
            fail("the OpenAL driver lacks ALC_EXT_thread_local_context")
            ALC10.alcCloseDevice(device)
            return
        }
        val context = ALC10.alcCreateContext(device, null as IntBuffer?)
        if (context == MemoryUtil.NULL || !EXTThreadLocalContext.alcSetThreadContext(context)) {
            fail("could not create an OpenAL context for voice")
            ALC10.alcCloseDevice(device)
            return
        }
        AL.setCurrentThread(AL.createCapabilities(alcCaps))

        val streams = HashMap<Int, SpeakerStream>()
        try {
            while (running) {
                while (true) {
                    val frame = queue.poll() ?: break
                    streams.getOrPut(frame.speaker) { SpeakerStream() }.push(frame)
                }
                while (true) {
                    val speaker = retire.poll() ?: break
                    streams.remove(speaker)?.close()
                }
                streams.forEach { (speaker, stream) -> stream.step(gainFor(speaker)) }
                Thread.sleep(TICK_MS)
            }
        } catch (e: Exception) {
            if (running) VoiceLog.logger.warn("playback thread stopped", e)
        } finally {
            streams.values.forEach { it.close() }
            EXTThreadLocalContext.alcSetThreadContext(MemoryUtil.NULL)
            AL.setCurrentThread(null)
            ALC10.alcDestroyContext(context)
            ALC10.alcCloseDevice(device)
        }
    }

    private fun fail(message: String) {
        error = message
        VoiceLog.logger.warn(message)
    }

    /**
     * One remote voice: a jitter buffer in front of an Opus decoder in front
     * of a streaming OpenAL source.
     *
     * Frames are held until [PREBUFFER_FRAMES] have arrived, then played in
     * sequence order. A missing frame is concealed by the decoder; a gap too
     * large to conceal is skipped. Playback stops when the sender signals the
     * end of a burst or after a short starvation, so a dropped sender does
     * not hiss forever.
     */
    private class SpeakerStream {
        private val source = AL10.alGenSources()
        private val decoder: OpusDecoder = Opus.decoder()
        private val pending = TreeMap<Int, ByteArray>(SEQUENCE_ORDER)
        private val freeBuffers = ArrayDeque<Int>()
        private val staging = BufferUtils.createShortBuffer(FRAME_SAMPLES)

        private var playing = false
        private var ended = false
        private var next = 0
        private var starved = 0

        init {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
        }

        fun push(frame: VoiceFrames.Frame) {
            if (frame.type == VoiceFrames.TYPE_END) {
                ended = true
                return
            }
            // Late frames are useless; the decoder already concealed them.
            if (playing && SEQUENCE_ORDER.compare(frame.sequence, next) < 0) return
            pending[frame.sequence] = frame.payload
        }

        fun step(gain: Float) {
            AL10.alSourcef(source, AL10.AL_GAIN, gain)

            repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
                freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source))
            }

            var queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
            while (queued < TARGET_QUEUED_FRAMES) {
                val pcm = nextPcm() ?: break
                val buffer = freeBuffers.removeFirstOrNull() ?: AL10.alGenBuffers()
                staging.clear()
                staging.put(pcm, 0, minOf(pcm.size, FRAME_SAMPLES))
                staging.flip()
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, staging, SAMPLE_RATE)
                AL10.alSourceQueueBuffers(source, buffer)
                queued++
            }

            if (queued > 0 && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                AL10.alSourcePlay(source)
            }
        }

        private fun nextPcm(): ShortArray? {
            if (!playing) {
                val ready = pending.size >= PREBUFFER_FRAMES || (ended && pending.isNotEmpty())
                if (!ready) return null
                playing = true
                starved = 0
                next = pending.firstKey()
            }

            if (pending.isEmpty()) {
                if (ended || ++starved > MAX_STARVED_FRAMES) {
                    finishBurst()
                    return null
                }
                // Nothing arrived in time: let the decoder fill the hole.
                return decoder.decode(null)
            }
            starved = 0

            val first = pending.firstKey()
            if (first != next && SEQUENCE_ORDER.compare(first, next) > 0) {
                if (sequenceDistance(next, first) > MAX_CONCEAL_FRAMES) {
                    next = first
                } else {
                    next++
                    return decoder.decode(null)
                }
            }
            val payload = pending.pollFirstEntry().value
            next++
            return decoder.decode(payload)
        }

        private fun finishBurst() {
            playing = false
            ended = false
            starved = 0
            pending.clear()
            decoder.resetState()
        }

        fun close() {
            runCatching { AL10.alSourceStop(source) }
            repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
                freeBuffers.addLast(AL10.alSourceUnqueueBuffers(source))
            }
            freeBuffers.forEach { AL10.alDeleteBuffers(it) }
            freeBuffers.clear()
            AL10.alDeleteSources(source)
            decoder.close()
        }

        private fun sequenceDistance(
            from: Int,
            to: Int,
        ): Int = to - from

        private companion object {
            /** Sequence numbers wrap, so compare them as a ring rather than as ints. */
            val SEQUENCE_ORDER: Comparator<Int> = Comparator { a, b -> (a - b).compareTo(0) }

            /** 40 ms of audio in hand before playback starts. */
            const val PREBUFFER_FRAMES = 2

            /** Keep this many frames queued on the source: 60 ms. */
            const val TARGET_QUEUED_FRAMES = 3

            /** Give up on a burst after 100 ms with nothing new. */
            const val MAX_STARVED_FRAMES = 5

            /** Conceal gaps up to this long; skip ahead past anything longer. */
            const val MAX_CONCEAL_FRAMES = 5
        }
    }

    private companion object {
        const val TICK_MS = 5L
    }
}
