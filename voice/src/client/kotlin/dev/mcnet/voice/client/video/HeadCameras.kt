package dev.mcnet.voice.client.video

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.mcnet.voice.client.MCNetVoiceClient
import dev.mcnet.voice.client.VoiceClientConfig
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Cameras drawn in the world, floating above the player they belong to just
 * over the name tag, and turned to face the viewer the way a name tag is.
 *
 * Two passes a frame, both on the render thread. Extraction decides what to
 * draw while touching the game is allowed: who is on camera, visible and
 * near enough, and where each tile goes. Submission turns that list into
 * geometry and nothing more. Tiles are depth tested, so a wall hides a
 * camera the way it hides the player behind it.
 *
 * Your own camera is drawn above your own head only when the camera is
 * behind you, and the right way round rather than mirrored, because that
 * is how everyone else sees you.
 */
object HeadCameras {
    private class Tile(
        val surface: VideoSurface,
        /** The bottom center of the tile, in world coordinates. */
        val position: Vec3,
        val speaking: Boolean,
    )

    /** Written by extraction, read by submission; same thread, same frame. */
    private var tiles: List<Tile> = emptyList()

    fun register() {
        LevelExtractionEvents.END_EXTRACTION.register { context -> tiles = extract(context) }
        LevelRenderEvents.COLLECT_SUBMITS.register { context -> submit(context) }
    }

    private fun extract(context: LevelExtractionContext): List<Tile> {
        if (!VoiceClientConfig.cameraPlacement.aboveHead || !VoiceClientConfig.showCameras) return emptyList()
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden) return emptyList()
        val session = MCNetVoiceClient.session ?: return emptyList()
        val viewer = minecraft.player ?: return emptyList()
        val level = context.level()
        val camera = context.camera()
        val partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true)

        val out = ArrayList<Tile>()
        for (entry in session.roster) {
            val self = entry.uuid == viewer.uuid
            if (self) {
                if (!camera.isDetached || session.camera == null || !session.preview.hasPicture) continue
            } else if (!entry.camera || VoiceClientConfig.isCameraHidden(entry.uuid)) {
                continue
            }
            val player = level.getPlayerByUUID(entry.uuid) ?: continue
            if (player.isSpectator || player.isInvisibleTo(viewer)) continue
            val feet = player.getPosition(partialTick)
            if (feet.distanceToSqr(camera.position()) > DRAW_RANGE * DRAW_RANGE) continue
            val surface = if (self) session.preview else session.receivers.surface(entry.speaker) ?: continue
            val speaking = if (self) session.isTransmitting else session.isSpeaking(entry.uuid)
            out += Tile(surface, feet.add(0.0, player.bbHeight + ABOVE_HEAD, 0.0), speaking)
        }
        return out
    }

    private fun submit(context: LevelRenderContext) {
        val tiles = tiles
        if (tiles.isEmpty()) return
        val camera = context.levelState().cameraRenderState
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()
        for (tile in tiles) {
            // Uploads the newest picture and makes sure the texture is registered.
            tile.surface.textureView() ?: continue
            val width = TILE_WIDTH
            val height = width * tile.surface.height / tile.surface.width.coerceAtLeast(1)

            poseStack.pushPose()
            poseStack.translate(tile.position.subtract(camera.pos))
            // Like a name tag: after this, +X is screen right and +Y is screen up.
            poseStack.rotate(camera.orientation)
            collector.submitCustomGeometry(
                poseStack,
                RenderTypes.entityTranslucentEmissive(tile.surface.id),
            ) { pose, consumer ->
                quad(pose, consumer, -width / 2, 0f, width / 2, height, WHITE)
            }
            if (tile.speaking) {
                // Four strips around the picture, in its plane, so nothing overlaps and nothing z-fights.
                collector.submitCustomGeometry(
                    poseStack,
                    RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
                ) { pose, consumer ->
                    val x0 = -width / 2
                    val x1 = width / 2
                    quad(pose, consumer, x0 - BORDER, -BORDER, x1 + BORDER, 0f, SPEAKING)
                    quad(pose, consumer, x0 - BORDER, height, x1 + BORDER, height + BORDER, SPEAKING)
                    quad(pose, consumer, x0 - BORDER, 0f, x0, height, SPEAKING)
                    quad(pose, consumer, x1, 0f, x1 + BORDER, height, SPEAKING)
                }
            }
            poseStack.popPose()
        }
    }

    /** A textured rectangle in the XY plane, with the texture's top row along the top edge. */
    private fun quad(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Int,
    ) {
        vertex(pose, consumer, x0, y0, 0f, 1f, color)
        vertex(pose, consumer, x1, y0, 1f, 1f, color)
        vertex(pose, consumer, x1, y1, 1f, 0f, color)
        vertex(pose, consumer, x0, y1, 0f, 0f, color)
    }

    private fun vertex(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        color: Int,
    ) {
        consumer
            .addVertex(pose, x, y, 0f)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(FULL_BRIGHT)
            .setNormal(pose, 0f, 0f, -1f)
    }

    /** Cameras further away than this are neither drawn nor, with cameras above heads only, fetched. */
    const val DRAW_RANGE = 32.0

    /** Tile width in blocks. The height follows the picture. */
    private const val TILE_WIDTH = 1f

    /** Gap between the top of the head and the tile, leaving room for the name tag. */
    private const val ABOVE_HEAD = 0.85

    private const val BORDER = 0.03f

    /** Block light 15, sky light 15: what the lightmap calls full brightness. */
    private const val FULL_BRIGHT = 0xF000F0

    private const val WHITE = -1
    private val SPEAKING = 0xFF55FF55.toInt()

    /** Vanilla's solid white texture, for the colored frame. */
    private val WHITE_TEXTURE: Identifier = Identifier.withDefaultNamespace("textures/misc/white.png")
}
