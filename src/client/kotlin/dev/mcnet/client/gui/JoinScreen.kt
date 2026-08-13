package dev.mcnet.client.gui

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import dev.mcnet.client.MCNetJoin
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import dev.mcnet.net.IrohRuntime

/**
 * Asks for a join code, dials the host over iroh, then hands Minecraft the
 * resulting loopback address.
 *
 * From the game's point of view this is an ordinary direct connect — which is
 * the whole trick.
 */
class JoinScreen(
    private val parent: Screen,
    /** Prefilled code, used when the peer list hands a failed join back here. */
    private val prefill: String? = null,
    /** Failure text to show on open, for the same reason. */
    private val initialError: String? = null,
) : Screen(Component.translatable("mcnet.join.title")) {

    private lateinit var codeBox: EditBox
    private lateinit var connectButton: Button
    private lateinit var status: StringWidget

    private var connecting = false

    override fun init() {
        val centreX = width / 2
        val top = height / 4

        addRenderableWidget(
            StringWidget(centreX - 150, top, 300, 20, title, font),
        )

        codeBox = EditBox(font, centreX - 150, top + 34, 300, 20, Component.translatable("mcnet.join.code"))
        // Tickets are long; the endpoint-id form is short. Allow room for both.
        codeBox.setMaxLength(1024)
        codeBox.setHint(Component.translatable("mcnet.join.hint").withStyle(ChatFormatting.DARK_GRAY))
        codeBox.setValue(prefill ?: lastCode)
        codeBox.moveCursorToEnd(false)
        codeBox.setResponder { lastCode = it }
        addRenderableWidget(codeBox)

        status = StringWidget(
            centreX - 150,
            top + 62,
            300,
            20,
            Component.translatable("mcnet.join.paste").withStyle(ChatFormatting.GRAY),
            font,
        )
        addRenderableWidget(status)

        connectButton = Button.builder(Component.translatable("mcnet.join.connect")) { connect() }
            .bounds(centreX - 154, top + 94, 150, 20)
            .build()
        addRenderableWidget(connectButton)

        addRenderableWidget(
            Button.builder(Component.translatable("gui.cancel")) { onClose() }
                .bounds(centreX + 4, top + 94, 150, 20)
                .build(),
        )

        // Showing this instance's own identity makes the "both instances copied
        // the same config" trap obvious instead of invisible.
        addRenderableWidget(
            StringWidget(
                centreX - 150,
                top + 122,
                300,
                20,
                Component.translatable("mcnet.join.identity", IrohRuntime.localIdShort())
                    .withStyle(ChatFormatting.DARK_GRAY),
                font,
            ),
        )

        initialError?.let {
            status.message = Component.literal(it).withStyle(ChatFormatting.RED)
        }

        setInitialFocus(codeBox)
    }

    private fun connect() {
        if (connecting) return
        val code = codeBox.value.trim()
        if (code.isEmpty()) {
            setStatus("mcnet.join.empty", ChatFormatting.RED)
            return
        }

        connecting = true
        connectButton.active = false
        setStatus("mcnet.join.connecting", ChatFormatting.YELLOW)

        MCNetJoin.start(code, parent) { reason ->
            connecting = false
            connectButton.active = true
            status.message = Component.literal(reason).withStyle(ChatFormatting.RED)
        }
    }

    private fun setStatus(key: String, colour: ChatFormatting) {
        status.message = Component.translatable(key).withStyle(colour)
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }

    companion object {
        /**
         * The last code typed, kept so reopening the screen does not lose it.
         *
         * Screens are rebuilt on every resize and discarded when closed, so
         * without this a player who wants to re-check the code they pasted
         * finds an empty box.
         */
        private var lastCode: String = ""
    }
}
