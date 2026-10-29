package dev.mcnet.voice.client

import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.client.video.VideoSurface
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Camera tiles along the top-right edge: this player's own camera first,
 * mirrored like a mirror would be, then everyone else's. A green frame marks
 * whoever is speaking, which is the one piece of voice state worth showing
 * on a face.
 */
object VideoHud : HudElement {
    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "cameras"), this)
    }

    private class Tile(
        val surface: VideoSurface,
        val label: Component,
        val speaking: Boolean,
        val mirrored: Boolean,
    )

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        deltaTracker: DeltaTracker,
    ) {
        if (!VoiceClientConfig.showCameras) return
        val session = MCNetVoiceClient.session ?: return
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player?.uuid

        val tiles = ArrayList<Tile>()
        if (session.camera != null && session.preview.hasPicture) {
            tiles += Tile(session.preview, Component.translatable("mcnet-voice.hud.you"), session.isTransmitting, true)
        }
        for (entry in session.roster) {
            if (entry.uuid == me || !entry.camera || VoiceClientConfig.isCameraHidden(entry.uuid)) continue
            val surface = session.receivers.surface(entry.speaker) ?: continue
            tiles += Tile(surface, Component.literal(entry.name), session.isSpeaking(entry.uuid), false)
        }
        if (tiles.isEmpty()) return

        val font = minecraft.font
        val tileWidth = VoiceClientConfig.cameraTileWidth
        var x = graphics.guiWidth() - MARGIN - tileWidth
        var y = MARGIN
        var rowHeight = 0
        for (tile in tiles) {
            val view = tile.surface.textureView() ?: continue
            val tileHeight = (tileWidth.toLong() * tile.surface.height / tile.surface.width.coerceAtLeast(1)).toInt()
            if (x < MARGIN) {
                // Wrap to the next row, below the tallest tile in this one.
                x = graphics.guiWidth() - MARGIN - tileWidth
                y += rowHeight + GAP
                rowHeight = 0
            }
            rowHeight = maxOf(rowHeight, tileHeight)

            graphics.fill(x - 1, y - 1, x + tileWidth + 1, y + tileHeight + 1, if (tile.speaking) SPEAKING else FRAME)
            val (u0, u1) = if (tile.mirrored) 1f to 0f else 0f to 1f
            graphics.blit(view, VideoSurface.SAMPLER, x, y, x + tileWidth, y + tileHeight, u0, u1, 0f, 1f)

            val labelY = y + tileHeight - font.lineHeight - 1
            graphics.fill(x, labelY - 1, x + font.width(tile.label) + 4, y + tileHeight, BACKDROP)
            graphics.text(font, tile.label, x + 2, labelY, WHITE, true)

            x -= tileWidth + GAP
        }
    }

    private const val MARGIN = 4
    private const val GAP = 4
    private const val WHITE = -1
    private val FRAME = 0xFF000000.toInt()
    private val SPEAKING = 0xFF55FF55.toInt()
    private val BACKDROP = 0x80000000.toInt()
}
