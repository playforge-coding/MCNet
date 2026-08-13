package dev.mcnet.client

import dev.mcnet.client.gui.HostScreen
import dev.mcnet.client.gui.PeerListScreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.network.chat.Component

/**
 * Adds MCNet's two entry points to vanilla screens.
 *
 * Done through Fabric's screen events rather than mixins: we only need to
 * append a widget, and an event keeps us clear of the layout internals that
 * shift between Minecraft versions.
 */
object MCNetScreens {
    private const val BUTTON_WIDTH = 90
    private const val BUTTON_HEIGHT = 20
    private const val MARGIN = 6

    fun register() {
        ScreenEvents.AFTER_INIT.register { minecraft, screen, width, _ ->
            when (screen) {
                is JoinMultiplayerScreen -> addJoinButton(screen, width)
                is PauseScreen -> addHostButton(screen, minecraft.hasSingleplayerServer())
                else -> Unit
            }
        }
    }

    /** "Join via MCNet" in the top-right of the server list. */
    private fun addJoinButton(screen: JoinMultiplayerScreen, width: Int) {
        val button = Button.builder(Component.translatable("mcnet.button.join")) {
            Minecraft.getInstance().gui.setScreen(PeerListScreen(screen))
        }.bounds(width - BUTTON_WIDTH - MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT).build()

        addWidget(screen, button)
    }

    /**
     * "Host with MCNet" on the pause menu, mirroring where "Open to LAN" lives.
     *
     * Only offered for singleplayer worlds — on a server the player is not the
     * host, and dedicated servers use `/mcnet host` instead.
     */
    private fun addHostButton(screen: PauseScreen, singleplayer: Boolean) {
        if (!singleplayer || !screen.showsPauseMenu()) return

        val button = Button.builder(Component.translatable("mcnet.button.host")) {
            Minecraft.getInstance().gui.setScreen(HostScreen(screen))
        }.bounds(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT).build()

        addWidget(screen, button)
    }

    private fun addWidget(screen: Screen, button: Button) {
        // Screen#addRenderableWidget is protected; the screen API exposes the
        // same list so widgets added here take part in rendering and input.
        net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(button)
    }
}
