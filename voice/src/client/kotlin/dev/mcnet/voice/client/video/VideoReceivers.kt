package dev.mcnet.voice.client.video

import dev.mcnet.video.ffi.Decoder
import dev.mcnet.video.ffi.VideoException
import dev.mcnet.voice.VideoEvent
import dev.mcnet.voice.VoiceLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes every incoming camera, one decoder per speaker, on a single
 * thread. Network callbacks only enqueue; all native calls happen here.
 *
 * Only speakers the session asked the host for are decoded (see [watch]),
 * and a decoder is created only when one of their groups begins, so frames
 * still in flight for someone who left or was hidden are dropped rather
 * than bringing a decoder back.
 *
 * Only the newest group of pictures counts. A frame for an older group is
 * dropped, and if this thread falls behind it stops decoding the current
 * group and resumes at the next keyframe, which is the same thing the relay
 * does for a slow link.
 */
class VideoReceivers {
    private class Receiver(
        speaker: Int,
    ) {
        val decoder = Decoder(1u)
        val surface = VideoSurface("speaker-$speaker")
        var group = -1
        var skipping = true
        var expectKeyframe = true

        @Volatile var lastPictureAt = 0L
    }

    private sealed interface Item {
        class Video(
            val speaker: Int,
            val group: Int,
            val event: VideoEvent,
        ) : Item

        /** The speakers to decode from now on; everyone else is released. */
        class Watch(
            val speakers: Set<Int>,
        ) : Item

        /** Frames from this speaker were dropped on the way in, so their group cannot be decoded. */
        class Gap(
            val speaker: Int,
        ) : Item

        data object Stop : Item
    }

    private val queue = LinkedBlockingQueue<Item>()

    /** Video items in [queue]. Bounded, so a stalled decoder cannot eat memory without limit. */
    private val pending = AtomicInteger()

    /** Speakers with a [Item.Gap] already queued, so a long overflow adds only one. */
    private val gapped: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val receivers = ConcurrentHashMap<Int, Receiver>()

    /** Decode thread only. */
    private var wanted: Set<Int> = emptySet()

    private val thread = Thread(::run, "mcnet-video-dec").apply { isDaemon = true }

    init {
        thread.start()
    }

    /** Network thread. */
    fun onVideo(
        speaker: Int,
        group: Int,
        event: VideoEvent,
    ) {
        if (pending.get() >= QUEUE_LIMIT) {
            if (gapped.add(speaker)) queue.add(Item.Gap(speaker))
            return
        }
        pending.incrementAndGet()
        queue.add(Item.Video(speaker, group, event))
    }

    /** Which speakers to decode. Anyone left out has their decoder released. */
    fun watch(speakers: Set<Int>) {
        queue.add(Item.Watch(speakers.toSet()))
    }

    /** The picture for [speaker], if one arrived recently enough to still count as live. */
    fun surface(speaker: Int): VideoSurface? =
        receivers[speaker]?.takeIf { System.currentTimeMillis() - it.lastPictureAt < LIVE_WINDOW_MS }?.surface

    fun close() {
        queue.add(Item.Stop)
    }

    private fun run() {
        while (true) {
            val item = queue.take()
            if (item is Item.Video) pending.decrementAndGet()
            try {
                when (item) {
                    Item.Stop -> {
                        receivers.values.forEach(::dispose)
                        receivers.clear()
                        return
                    }

                    is Item.Watch -> {
                        wanted = item.speakers
                        (receivers.keys - wanted).forEach { receivers.remove(it)?.let(::dispose) }
                    }

                    is Item.Gap -> {
                        gapped.remove(item.speaker)
                        receivers[item.speaker]?.skipping = true
                    }

                    is Item.Video -> {
                        handle(item)
                    }
                }
            } catch (t: Throwable) {
                // Whatever went wrong belongs to one speaker; the others keep
                // playing. That speaker starts afresh at their next group.
                VoiceLog.logger.warn("video decoding failed", t)
                (item as? Item.Video)?.let { receivers.remove(it.speaker)?.let(::dispose) }
            }
        }
    }

    private fun handle(item: Item.Video) {
        if (item.speaker !in wanted) return
        val receiver =
            if (item.event == VideoEvent.Begin) {
                receivers.getOrPut(item.speaker) { Receiver(item.speaker) }
            } else {
                receivers[item.speaker] ?: return
            }
        when (val event = item.event) {
            VideoEvent.Begin -> {
                if (item.group > receiver.group) {
                    receiver.group = item.group
                    receiver.skipping = false
                    receiver.expectKeyframe = true
                }
            }

            is VideoEvent.Frame -> {
                if (item.group != receiver.group || receiver.skipping) return
                if (pending.get() > BACKLOG) {
                    // Behind by seconds: let this group go and catch the next.
                    receiver.skipping = true
                    return
                }
                if (receiver.expectKeyframe && !event.keyframe) {
                    receiver.skipping = true
                    return
                }
                receiver.expectKeyframe = false
                try {
                    receiver.decoder.decode(event.data)?.use { picture ->
                        receiver.surface.present(
                            picture.width().toInt(),
                            picture.height().toInt(),
                        ) { address, capacity ->
                            picture.writeRgba(address.toULong(), capacity.toULong())
                        }
                        receiver.lastPictureAt = System.currentTimeMillis()
                    }
                } catch (e: VideoException) {
                    VoiceLog.logger.debug("dropping a video group from speaker {}", item.speaker, e)
                    receiver.skipping = true
                }
            }

            VideoEvent.End -> {}
        }
    }

    private fun dispose(receiver: Receiver) {
        runCatching { receiver.surface.close() }
        runCatching { receiver.decoder.close() }
    }

    private companion object {
        /** A tile whose last picture is older than this is not drawn. */
        const val LIVE_WINDOW_MS = 3_000L

        /** Queued frames across all speakers before the decoder starts skipping groups. */
        const val BACKLOG = 90

        /** Queued frames before new ones are refused outright: several seconds of several cameras. */
        const val QUEUE_LIMIT = 600
    }
}
