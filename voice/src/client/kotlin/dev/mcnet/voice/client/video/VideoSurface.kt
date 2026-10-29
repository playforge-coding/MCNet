package dev.mcnet.voice.client.video

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.mcnet.voice.VoiceLog
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil

/**
 * One video picture as a GPU texture, fed from a decode thread and drawn on
 * the render thread.
 *
 * Pixels take three steps: the decoder writes RGBA into an off-heap back
 * buffer, which then becomes the front buffer, and the render thread copies
 * the front buffer into the texture's own image before uploading it. The lock
 * is held only for the swap and the copy, never while decoding or uploading,
 * so neither thread waits on the other's slow part.
 */
class VideoSurface(
    private val name: String,
) {
    val id: Identifier = Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "video/$name")

    private val lock = Any()
    private var back: NativeImage? = null
    private var front: NativeImage? = null
    private var dirty = false
    private var closed = false

    /** Render thread only. */
    private var texture: DynamicTexture? = null

    @Volatile var width: Int = 0
        private set

    @Volatile var height: Int = 0
        private set

    val hasPicture: Boolean get() = width > 0

    /**
     * Any thread. [writer] fills [width] x [height] RGBA at the address it
     * is given, with the capacity in bytes; the picture is shown at the
     * next render.
     */
    fun present(
        width: Int,
        height: Int,
        writer: (address: Long, capacity: Long) -> Unit,
    ) {
        val image =
            synchronized(lock) {
                if (closed) return
                var buffer = back
                if (buffer == null || buffer.width != width || buffer.height != height) {
                    buffer?.close()
                    buffer = NativeImage(width, height, false)
                }
                back = null
                buffer
            }
        writer(image.pointer, width.toLong() * height * 4)
        synchronized(lock) {
            if (closed) {
                image.close()
                return
            }
            back = front
            front = image
            dirty = true
            this.width = width
            this.height = height
        }
    }

    /**
     * Render thread. Uploads the newest picture if there is one and returns
     * the texture to draw, or null before the first picture arrives.
     */
    fun textureView(): GpuTextureView? {
        var upload = false
        synchronized(lock) {
            if (closed) return null
            val picture = front ?: return null
            if (dirty) {
                var current = texture
                if (current == null || current.pixels?.width != picture.width ||
                    current.pixels?.height != picture.height
                ) {
                    current?.close()
                    current = DynamicTexture({ "mcnet-voice $name" }, NativeImage(picture.width, picture.height, false))
                    texture = current
                }
                val pixels = current.pixels ?: return null
                MemoryUtil.memCopy(picture.pointer, pixels.pointer, picture.width.toLong() * picture.height * 4)
                dirty = false
                upload = true
            }
        }
        val current = texture ?: return null
        if (upload) current.upload()
        return current.textureView
    }

    fun close() {
        synchronized(lock) {
            closed = true
            back?.close()
            front?.close()
            back = null
            front = null
            width = 0
            height = 0
        }
        // GPU resources belong to the render thread.
        Minecraft.getInstance().execute {
            texture?.close()
            texture = null
        }
    }

    companion object {
        /** Webcam tiles are drawn far smaller than they are decoded; nearest sampling would shimmer. */
        val SAMPLER: GpuSampler by lazy { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
    }
}
