package dev.mcnet.voice.client.gui

import dev.mcnet.voice.RosterEntry
import dev.mcnet.voice.client.MCNetVoiceClient
import dev.mcnet.voice.client.VoiceClientConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Everyone else in the call, each with a mute toggle, a volume slider and a
 * camera toggle. All three are local to this client and persist across
 * sessions by player UUID.
 */
class VoicePlayersScreen(
    private val parent: Screen,
) : Screen(Component.translatable("mcnet-voice.players.title")) {
    private val layout = HeaderAndFooterLayout(this)
    private lateinit var list: PlayerList
    private var shown: List<UUID> = emptyList()

    override fun init() {
        layout.addTitleHeader(title, font)
        list = layout.addToContents(PlayerList(minecraft, width, layout.contentHeight, layout.headerHeight))
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets { addRenderableWidget(it) }
        repositionElements()
        reload()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        if (::list.isInitialized) list.updateSize(width, layout)
    }

    override fun tick() {
        // The roster changes as people come and go; rebuild only when it does,
        // so a slider being dragged is not yanked out from under the mouse.
        if (participants().map { it.uuid } != shown) reload()
    }

    private fun participants(): List<RosterEntry> = MCNetVoiceClient.session?.others.orEmpty()

    private fun reload() {
        val entries = participants()
        shown = entries.map { it.uuid }
        list.reload(entries)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        if (shown.isEmpty()) {
            val message = Component.translatable("mcnet-voice.players.empty")
            graphics.text(font, message, (width - font.width(message)) / 2, height / 2 - font.lineHeight, GRAY)
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    inner class PlayerList(
        minecraft: Minecraft,
        width: Int,
        height: Int,
        y: Int,
    ) : ContainerObjectSelectionList<PlayerList.PlayerEntry>(minecraft, width, height, y, ROW_HEIGHT) {
        fun reload(entries: List<RosterEntry>) {
            clearEntries()
            entries.forEach { addEntry(PlayerEntry(it)) }
        }

        override fun getRowWidth(): Int = ROW_WIDTH

        inner class PlayerEntry(
            private val participant: RosterEntry,
        ) : ContainerObjectSelectionList.Entry<PlayerEntry>() {
            private val muteButton: Button =
                Button
                    .builder(muteLabel()) {
                        VoiceClientConfig.setMuted(participant.uuid, !VoiceClientConfig.isMuted(participant.uuid))
                        it.message = muteLabel()
                    }.width(MUTE_WIDTH)
                    .build()

            private val volume =
                ValueSlider(
                    SLIDER_WIDTH,
                    0.0,
                    2.0,
                    VoiceClientConfig.volumeOf(participant.uuid),
                    { Component.translatable("mcnet-voice.players.volume", (it * 100).roundToInt()) },
                ) { VoiceClientConfig.setVolume(participant.uuid, it) }

            private val cameraButton: Button =
                Button
                    .builder(cameraLabel()) {
                        val hidden = !VoiceClientConfig.isCameraHidden(participant.uuid)
                        VoiceClientConfig.setCameraHidden(participant.uuid, hidden)
                        MCNetVoiceClient.session?.refreshWatches()
                        it.message = cameraLabel()
                    }.width(CAMERA_WIDTH)
                    .build()

            private fun muteLabel(): Component =
                Component.translatable(
                    if (VoiceClientConfig.isMuted(participant.uuid)) {
                        "mcnet-voice.players.unmute"
                    } else {
                        "mcnet-voice.players.mute"
                    },
                )

            private fun cameraLabel(): Component =
                Component.translatable(
                    if (VoiceClientConfig.isCameraHidden(participant.uuid)) {
                        "mcnet-voice.players.show_camera"
                    } else {
                        "mcnet-voice.players.hide_camera"
                    },
                )

            override fun children(): List<GuiEventListener> = listOf(volume, muteButton, cameraButton)

            override fun narratables(): List<NarratableEntry> = listOf(volume, muteButton, cameraButton)

            override fun extractContent(
                graphics: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                partialTick: Float,
            ) {
                val font = this@VoicePlayersScreen.font
                val textY = contentY + (ROW_HEIGHT - font.lineHeight) / 2 - 1
                graphics.text(font, participant.name, contentX + 4, textY, WHITE)

                val state =
                    when {
                        MCNetVoiceClient.session?.isSpeaking(participant.uuid) == true -> "mcnet-voice.players.speaking"
                        participant.camera -> "mcnet-voice.players.on_camera"
                        else -> null
                    }
                if (state != null) {
                    graphics.text(
                        font,
                        Component.translatable(state),
                        contentX + 8 + font.width(participant.name),
                        textY,
                        GREEN,
                    )
                }

                cameraButton.x = contentRight - CAMERA_WIDTH
                cameraButton.y = contentY + 1
                muteButton.x = cameraButton.x - 4 - MUTE_WIDTH
                muteButton.y = contentY + 1
                volume.x = muteButton.x - 4 - SLIDER_WIDTH
                volume.y = contentY + 1
                volume.extractRenderState(graphics, mouseX, mouseY, partialTick)
                muteButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
                cameraButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            }
        }
    }

    private companion object {
        const val ROW_HEIGHT = 24
        const val ROW_WIDTH = 400
        const val MUTE_WIDTH = 60
        const val CAMERA_WIDTH = 70
        const val SLIDER_WIDTH = 100

        const val WHITE = -1
        const val GRAY = -8355712
        val GREEN = 0xFF55FF55.toInt()
    }
}
