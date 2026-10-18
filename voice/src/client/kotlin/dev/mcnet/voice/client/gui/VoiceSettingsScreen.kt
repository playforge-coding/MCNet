package dev.mcnet.voice.client.gui

import dev.mcnet.voice.client.MCNetVoiceClient
import dev.mcnet.voice.client.VoiceClientConfig
import dev.mcnet.voice.client.VoiceSession
import dev.mcnet.voice.client.audio.AudioDevices
import dev.mcnet.voice.client.audio.Microphone
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * Every voice setting on one screen, with a live microphone meter under the
 * activation threshold so the player can see what the threshold means.
 *
 * Opening it outside a session starts a capture-only microphone for the
 * meter; closing it stops that again.
 */
class VoiceSettingsScreen(
    private val parent: Screen,
) : Screen(Component.translatable("mcnet-voice.settings.title")) {
    private val layout = HeaderAndFooterLayout(this)

    private lateinit var thresholdSlider: ValueSlider
    private lateinit var status: MultiLineTextWidget

    /** Capture-only microphone used when there is no session to borrow one from. */
    private var monitor: Microphone? = null

    override fun init() {
        layout.addTitleHeader(title, font)

        val contents = layout.addToContents(LinearLayout.vertical().spacing(ROW_SPACING))
        contents.defaultCellSetting().alignHorizontallyCenter()

        fun row(
            left: AbstractWidget,
            right: AbstractWidget,
        ) {
            val row = contents.addChild(LinearLayout.horizontal().spacing(COLUMN_SPACING))
            row.addChild(left)
            row.addChild(right)
        }

        val enabled =
            CycleButton
                .onOffBuilder(VoiceClientConfig.enabled)
                .create(Component.translatable("mcnet-voice.settings.enabled")) { _, value ->
                    MCNetVoiceClient.setEnabled(value)
                }
        enabled.width = HALF_WIDTH

        val activation =
            CycleButton
                .builder<VoiceClientConfig.Activation>({ activationLabel(it) }, VoiceClientConfig.activation)
                .withValues(VoiceClientConfig.Activation.entries)
                .create(Component.translatable("mcnet-voice.settings.activation")) { _, value ->
                    VoiceClientConfig.activation = value
                }
        activation.width = HALF_WIDTH
        row(enabled, activation)

        thresholdSlider =
            ValueSlider(
                HALF_WIDTH,
                METER_FLOOR_DB,
                0.0,
                VoiceClientConfig.thresholdDb,
                { Component.translatable("mcnet-voice.settings.threshold", it.roundToInt()) },
            ) { VoiceClientConfig.thresholdDb = it }
        thresholdSlider.setTooltip(Tooltip.create(Component.translatable("mcnet-voice.settings.threshold.hint")))

        val micGain =
            ValueSlider(
                HALF_WIDTH,
                0.0,
                3.0,
                VoiceClientConfig.microphoneGain,
                { Component.translatable("mcnet-voice.settings.mic_gain", (it * 100).roundToInt()) },
            ) { VoiceClientConfig.microphoneGain = it }
        row(thresholdSlider, micGain)

        val volume =
            ValueSlider(
                HALF_WIDTH,
                0.0,
                2.0,
                VoiceClientConfig.volume,
                { Component.translatable("mcnet-voice.settings.volume", (it * 100).roundToInt()) },
            ) { VoiceClientConfig.volume = it }

        val hud =
            CycleButton
                .onOffBuilder(VoiceClientConfig.hud)
                .create(Component.translatable("mcnet-voice.settings.hud")) { _, value ->
                    VoiceClientConfig.hud = value
                }
        hud.width = HALF_WIDTH
        row(volume, hud)

        val microphone =
            deviceButton("mcnet-voice.settings.microphone", AudioDevices.inputs(), VoiceClientConfig.microphone) {
                VoiceClientConfig.microphone = it
                onDevicesChanged()
            }
        val speaker =
            deviceButton("mcnet-voice.settings.speaker", AudioDevices.outputs(), VoiceClientConfig.speaker) {
                VoiceClientConfig.speaker = it
                onDevicesChanged()
            }
        row(microphone, speaker)

        status = contents.addChild(MultiLineTextWidget(Component.empty(), font).setMaxWidth(FULL_WIDTH))

        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(COLUMN_SPACING * 2))
        footer.addChild(
            Button
                .builder(Component.translatable("mcnet-voice.settings.players")) {
                    minecraft.gui.setScreen(VoicePlayersScreen(this))
                }.width(HALF_WIDTH)
                .build(),
        )
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(HALF_WIDTH).build())

        layout.visitWidgets { addRenderableWidget(it) }
        repositionElements()
        refreshStatus()
        ensureMonitor()
    }

    private fun deviceButton(
        key: String,
        devices: List<String>,
        current: String,
        onChange: (String) -> Unit,
    ): CycleButton<String> {
        // The saved device may be unplugged right now; keep it selectable so
        // the setting is not silently lost.
        val values = (listOf("") + devices + listOf(current)).distinct()
        val button =
            CycleButton
                .builder<String>({ deviceLabel(it) }, current)
                .withValues(values)
                .create(Component.translatable(key, "")) { _, value -> onChange(value) }
        button.width = HALF_WIDTH
        return button
    }

    private fun deviceLabel(device: String): Component =
        if (device.isEmpty()) {
            Component.translatable("mcnet-voice.settings.device.default")
        } else {
            Component.literal(AudioDevices.displayName(device))
        }

    private fun activationLabel(activation: VoiceClientConfig.Activation): Component =
        when (activation) {
            VoiceClientConfig.Activation.PUSH_TO_TALK -> {
                Component.translatable(
                    "mcnet-voice.settings.activation.push_to_talk",
                )
            }

            VoiceClientConfig.Activation.VOICE -> {
                Component.translatable("mcnet-voice.settings.activation.voice")
            }
        }

    override fun repositionElements() {
        layout.arrangeElements()
    }

    override fun tick() {
        refreshStatus()
    }

    private fun refreshStatus() {
        val session = MCNetVoiceClient.session
        val micError = currentMicrophone()?.error
        status.message =
            when {
                micError != null -> {
                    Component.translatable("mcnet-voice.settings.status.error", micError).withStyle(ChatFormatting.RED)
                }

                session == null -> {
                    Component.translatable("mcnet-voice.settings.status.idle").withStyle(ChatFormatting.GRAY)
                }

                session.state == VoiceSession.State.LIVE -> {
                    Component
                        .translatable("mcnet-voice.settings.status.live", session.others.size)
                        .withStyle(ChatFormatting.GREEN)
                }

                session.state == VoiceSession.State.CONNECTING -> {
                    Component.translatable("mcnet-voice.settings.status.connecting").withStyle(ChatFormatting.YELLOW)
                }

                else -> {
                    Component
                        .translatable("mcnet-voice.settings.status.error", session.error ?: "unknown")
                        .withStyle(ChatFormatting.RED)
                }
            }
    }

    private fun onDevicesChanged() {
        MCNetVoiceClient.session?.restartAudio()
        monitor?.stop()
        monitor = null
        ensureMonitor()
    }

    private fun ensureMonitor() {
        if (MCNetVoiceClient.session?.microphone == null && monitor == null) {
            monitor = Microphone(VoiceClientConfig.microphone, null).start()
        }
    }

    private fun currentMicrophone(): Microphone? = MCNetVoiceClient.session?.microphone ?: monitor

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        drawMeter(graphics)
    }

    /** A level bar under the threshold slider, with the threshold as a tick mark. */
    private fun drawMeter(graphics: GuiGraphicsExtractor) {
        val mic = currentMicrophone() ?: return
        val x = thresholdSlider.x
        val y = thresholdSlider.y + thresholdSlider.height + 2
        val width = thresholdSlider.width

        graphics.fill(x, y, x + width, y + METER_HEIGHT, METER_BACKGROUND)

        val level = ((mic.level - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0.0, 1.0)
        val open = mic.transmitting || mic.level > VoiceClientConfig.thresholdDb
        graphics.fill(x, y, x + (width * level).toInt(), y + METER_HEIGHT, if (open) METER_OPEN else METER_CLOSED)

        val marker = x + (width * ((VoiceClientConfig.thresholdDb - METER_FLOOR_DB) / -METER_FLOOR_DB)).toInt()
        graphics.fill(marker, y - 1, marker + 1, y + METER_HEIGHT + 1, METER_MARKER)
    }

    override fun removed() {
        monitor?.stop()
        monitor = null
        VoiceClientConfig.save()
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val HALF_WIDTH = 150
        const val COLUMN_SPACING = 4
        const val FULL_WIDTH = HALF_WIDTH * 2 + COLUMN_SPACING
        const val ROW_SPACING = 10
        const val METER_HEIGHT = 4
        const val METER_FLOOR_DB = -80.0

        val METER_BACKGROUND = 0xFF202020.toInt()
        val METER_CLOSED = 0xFF808080.toInt()
        val METER_OPEN = 0xFF55FF55.toInt()
        val METER_MARKER = 0xFFFFFFFF.toInt()
    }
}
