package dev.mcnet.voice.client.gui

import dev.mcnet.voice.client.MCNetVoiceClient
import dev.mcnet.voice.client.VoiceClientConfig
import dev.mcnet.voice.client.VoiceSession
import dev.mcnet.voice.client.audio.AudioDevices
import dev.mcnet.voice.client.audio.Microphone
import dev.mcnet.voice.client.video.VideoNative
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
 * Every voice and video setting on one screen, with a live microphone meter
 * under the activation threshold so the player can see what the threshold
 * means.
 *
 * Opening it outside a session starts a capture-only microphone for the
 * meter; closing it stops that again.
 */
class VoiceSettingsScreen(
    private val parent: Screen,
) : Screen(Component.translatable("mcnet-voice.settings.title")) {
    private val layout = HeaderAndFooterLayout(this)

    private lateinit var thresholdSlider: ValueSlider
    private lateinit var cameraToggle: CycleButton<Boolean>
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
            deviceButton(
                "mcnet-voice.settings.microphone",
                AudioDevices.inputs().map { it to AudioDevices.displayName(it) },
                VoiceClientConfig.microphone,
            ) {
                VoiceClientConfig.microphone = it
                onDevicesChanged()
            }
        val speaker =
            deviceButton(
                "mcnet-voice.settings.speaker",
                AudioDevices.outputs().map { it to AudioDevices.displayName(it) },
                VoiceClientConfig.speaker,
            ) {
                VoiceClientConfig.speaker = it
                onDevicesChanged()
            }
        row(microphone, speaker)

        // Camera. The on/off switch is for this session only; it never
        // persists, so nobody's camera comes on by surprise.
        val session = MCNetVoiceClient.session
        cameraToggle =
            CycleButton
                .onOffBuilder(session?.camera != null)
                .create(Component.translatable("mcnet-voice.settings.camera")) { _, value ->
                    MCNetVoiceClient.session?.setCamera(value)
                }
        cameraToggle.width = HALF_WIDTH
        cameraToggle.active = VideoNative.available && session?.state == VoiceSession.State.LIVE
        if (!VideoNative.available) {
            cameraToggle.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "mcnet-voice.settings.camera.unavailable",
                        VideoNative.unavailableReason ?: "",
                    ),
                ),
            )
        }

        val cameraDevice =
            deviceButton(
                "mcnet-voice.settings.camera_device",
                VideoNative.cameras().map { it.id to it.name },
                VoiceClientConfig.camera,
            ) {
                VoiceClientConfig.camera = it
                MCNetVoiceClient.session?.restartCamera()
            }
        cameraDevice.active = VideoNative.available
        row(cameraToggle, cameraDevice)

        val quality =
            CycleButton
                .builder<VoiceClientConfig.CameraQuality>({ qualityLabel(it) }, VoiceClientConfig.cameraQuality)
                .withValues(VoiceClientConfig.CameraQuality.entries)
                .create(Component.translatable("mcnet-voice.settings.camera_quality")) { _, value ->
                    VoiceClientConfig.cameraQuality = value
                    MCNetVoiceClient.session?.restartCamera()
                }
        quality.width = HALF_WIDTH

        val showCameras =
            CycleButton
                .onOffBuilder(VoiceClientConfig.showCameras)
                .create(Component.translatable("mcnet-voice.settings.show_cameras")) { _, value ->
                    VoiceClientConfig.showCameras = value
                    MCNetVoiceClient.session?.refreshWatches()
                }
        showCameras.width = HALF_WIDTH
        row(quality, showCameras)

        val tileWidth =
            ValueSlider(
                FULL_WIDTH,
                VoiceClientConfig.MIN_TILE_WIDTH.toDouble(),
                VoiceClientConfig.MAX_TILE_WIDTH.toDouble(),
                VoiceClientConfig.cameraTileWidth.toDouble(),
                { Component.translatable("mcnet-voice.settings.camera_size", it.roundToInt()) },
            ) { VoiceClientConfig.cameraTileWidth = it.roundToInt() }
        contents.addChild(tileWidth)

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

    /**
     * A cycle button over devices given as (id, display name). The saved
     * device may be unplugged right now; it stays selectable so the setting
     * is not silently lost.
     */
    private fun deviceButton(
        key: String,
        devices: List<Pair<String, String>>,
        current: String,
        onChange: (String) -> Unit,
    ): CycleButton<String> {
        val names = devices.toMap()
        val values = (listOf("") + devices.map { it.first } + listOf(current)).distinct()
        val button =
            CycleButton
                .builder<String>({ id ->
                    if (id.isEmpty()) {
                        Component.translatable("mcnet-voice.settings.device.default")
                    } else {
                        Component.literal(names[id] ?: id)
                    }
                }, current)
                .withValues(values)
                .create(Component.translatable(key, "")) { _, value -> onChange(value) }
        button.width = HALF_WIDTH
        return button
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

    private fun qualityLabel(quality: VoiceClientConfig.CameraQuality): Component =
        Component.translatable("mcnet-voice.settings.camera_quality.value", quality.height, quality.kbps)

    override fun repositionElements() {
        layout.arrangeElements()
    }

    override fun tick() {
        refreshStatus()
        val session = MCNetVoiceClient.session
        cameraToggle.active = VideoNative.available && session?.state == VoiceSession.State.LIVE
        // The key binding can flip the camera while this screen is open.
        val on = session?.camera != null
        if (cameraToggle.value != on) cameraToggle.value = on
    }

    private fun refreshStatus() {
        val session = MCNetVoiceClient.session
        val micError = currentMicrophone()?.error
        val cameraError = session?.cameraError
        val camera = session?.camera
        status.message =
            when {
                micError != null -> {
                    Component.translatable("mcnet-voice.settings.status.error", micError).withStyle(ChatFormatting.RED)
                }

                cameraError != null -> {
                    Component
                        .translatable("mcnet-voice.settings.status.camera_error", cameraError)
                        .withStyle(ChatFormatting.RED)
                }

                session == null -> {
                    Component.translatable("mcnet-voice.settings.status.idle").withStyle(ChatFormatting.GRAY)
                }

                session.state == VoiceSession.State.LIVE && camera != null && camera.description.isNotEmpty() -> {
                    Component
                        .translatable(
                            "mcnet-voice.settings.status.camera_live",
                            session.others.size,
                            camera.description,
                            camera.kbps,
                        ).append(rangeNote(session))
                        .withStyle(ChatFormatting.GREEN)
                }

                session.state == VoiceSession.State.LIVE -> {
                    Component
                        .translatable("mcnet-voice.settings.status.live", session.others.size)
                        .append(rangeNote(session))
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

    /**
     * How far voices carry on this host, which the host decides, not this
     * screen. Says nothing until the host has said, rather than guessing.
     */
    private fun rangeNote(session: VoiceSession): Component {
        val range = session.range ?: return Component.empty()
        return if (range > 0f) {
            Component.translatable("mcnet-voice.settings.status.range", range.roundToInt())
        } else {
            Component.translatable("mcnet-voice.settings.status.no_range")
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
