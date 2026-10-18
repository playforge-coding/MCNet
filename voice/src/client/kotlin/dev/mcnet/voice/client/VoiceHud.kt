package dev.mcnet.voice.client

import dev.mcnet.voice.VoiceLog
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * A small status stack in the bottom-right corner: who is speaking, and
 * whether this player's own microphone is open, muted or disconnected.
 *
 * Text on a translucent backdrop rather than sprites, so it needs no
 * texture atlas entries and survives resource pack changes.
 */
object VoiceHud : HudElement {
    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "status"), this)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        deltaTracker: DeltaTracker,
    ) {
        if (!VoiceClientConfig.hud) return
        val session = MCNetVoiceClient.session ?: return
        val font = Minecraft.getInstance().font

        val lines = ArrayList<Component>()
        statusLine(session)?.let { lines += it }
        session
            .speaking()
            .filter { it.uuid != Minecraft.getInstance().player?.uuid }
            .forEach { lines += Component.literal(it.name).withStyle(ChatFormatting.GREEN) }
        if (lines.isEmpty()) return

        // Stacked upward from the corner, status line at the bottom.
        var y = graphics.guiHeight() - MARGIN - font.lineHeight
        lines.forEach { line ->
            val width = font.width(line)
            val x = graphics.guiWidth() - MARGIN - width
            graphics.fill(x - PADDING, y - PADDING, x + width + PADDING, y + font.lineHeight + PADDING - 1, BACKDROP)
            graphics.text(font, line, x, y, WHITE, true)
            y -= font.lineHeight + PADDING * 2
        }
    }

    private fun statusLine(session: VoiceSession): Component? =
        when {
            session.state == VoiceSession.State.FAILED -> {
                Component.translatable("mcnet-voice.hud.disconnected").withStyle(ChatFormatting.RED)
            }

            session.state == VoiceSession.State.CONNECTING -> {
                Component.translatable("mcnet-voice.settings.status.connecting").withStyle(ChatFormatting.GRAY)
            }

            VoiceClientConfig.micMuted -> {
                Component.translatable("mcnet-voice.hud.muted").withStyle(ChatFormatting.RED)
            }

            session.isTransmitting -> {
                Component.translatable("mcnet-voice.hud.talking").withStyle(ChatFormatting.GREEN)
            }

            else -> {
                null
            }
        }

    private const val MARGIN = 4
    private const val PADDING = 2
    private const val WHITE = -1
    private val BACKDROP = 0x80000000.toInt()
}
