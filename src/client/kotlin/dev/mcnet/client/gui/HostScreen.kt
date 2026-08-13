package dev.mcnet.client.gui

import dev.mcnet.MCNet
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Opens the current singleplayer world to the internet over iroh and shows the
 * join codes.
 *
 * This is the singleplayer counterpart to `/mcnet host`, and exists mainly so
 * that hosting does not require cheats to be enabled just to run a command.
 */
class HostScreen(private val parent: Screen) : Screen(Component.translatable("mcnet.host.title")) {

    private lateinit var status: StringWidget
    private lateinit var codeBox: EditBox
    private lateinit var actionButton: Button

    private var starting = false

    override fun init() {
        val centreX = width / 2
        val top = height / 4

        addRenderableWidget(StringWidget(centreX - 150, top, 300, 20, title, font))

        status = StringWidget(centreX - 150, top + 30, 300, 20, Component.empty(), font)
        addRenderableWidget(status)

        // An edit box rather than a label: it makes the code selectable and
        // copyable with the usual keyboard shortcuts, which a StringWidget
        // cannot offer.
        codeBox = EditBox(font, centreX - 150, top + 56, 300, 20, Component.translatable("mcnet.host.code"))
        codeBox.setMaxLength(1024)
        codeBox.setEditable(false)
        addRenderableWidget(codeBox)

        actionButton = Button.builder(Component.empty()) { toggleHosting() }
            .bounds(centreX - 154, top + 90, 150, 20)
            .build()
        addRenderableWidget(actionButton)

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(centreX + 4, top + 90, 150, 20)
                .build(),
        )

        refresh()
    }

    private fun refresh() {
        val hosting = MCNet.isHosting
        actionButton.message = Component.translatable(
            if (hosting) "mcnet.host.stop" else "mcnet.host.start",
        )
        actionButton.active = !starting

        when {
            starting -> status.message =
                Component.translatable("mcnet.host.starting").withStyle(ChatFormatting.YELLOW)

            hosting -> status.message = Component.translatable(
                "mcnet.host.live",
                MCNet.host?.peerCount ?: 0,
            ).withStyle(ChatFormatting.GREEN)

            else -> {
                status.message =
                    Component.translatable("mcnet.host.idle").withStyle(ChatFormatting.GRAY)
                codeBox.value = ""
            }
        }
    }

    private fun toggleHosting() {
        val minecraft = Minecraft.getInstance()

        if (MCNet.isHosting) {
            MCNet.stopHosting()
            refresh()
            return
        }

        val server = minecraft.singleplayerServer
        if (server == null) {
            status.message =
                Component.translatable("mcnet.host.nosingleplayer").withStyle(ChatFormatting.RED)
            return
        }

        starting = true
        refresh()

        MCNet.startHosting(server) { result ->
            minecraft.execute {
                starting = false
                result.fold(
                    onSuccess = { codes ->
                        // The endpoint id is the code worth showing: it is
                        // short and it stays valid after the host changes
                        // network.
                        codeBox.value = codes.endpointId
                        refresh()
                    },
                    onFailure = { error ->
                        MCNet.logger.error("hosting failed", error)
                        refresh()
                        status.message = Component.literal(error.message ?: "Could not start hosting")
                            .withStyle(ChatFormatting.RED)
                    },
                )
            }
        }
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }
}
