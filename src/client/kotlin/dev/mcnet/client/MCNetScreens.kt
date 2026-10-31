package dev.mcnet.client

import dev.mcnet.client.gui.HostScreen
import dev.mcnet.client.gui.PeerListScreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.network.chat.Component
import java.util.WeakHashMap

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

    /** The button we put on each live screen, so re-inits reuse it. */
    private val added = WeakHashMap<Screen, Button>()

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
    private fun addJoinButton(
        screen: JoinMultiplayerScreen,
        width: Int,
    ) {
        val button =
            Button
                .builder(Component.translatable("mcnet.button.join")) {
                    Minecraft.getInstance().gui.setScreen(PeerListScreen(screen))
                }.bounds(width - BUTTON_WIDTH - MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build()

        addWidget(screen, button)
    }

    /**
     * "Host with MCNet" on the pause menu, next to vanilla's own LAN controls.
     *
     * Only offered for singleplayer worlds. On a server the player is not the
     * host, and dedicated servers use `/mcnet host` instead.
     */
    private fun addHostButton(
        screen: PauseScreen,
        singleplayer: Boolean,
    ) {
        if (!singleplayer || !screen.showsPauseMenu()) return

        val button =
            Button
                .builder(Component.translatable("mcnet.button.host")) {
                    Minecraft.getInstance().gui.setScreen(HostScreen(screen))
                }.bounds(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build()

        addWidget(screen, button)
    }

    private fun addWidget(
        screen: Screen,
        button: Button,
    ) {
        // Screen#addRenderableWidget is protected; the screen API exposes the
        // same list so widgets added here take part in rendering and input.
        val widgets = Screens.getWidgets(screen)

        // AFTER_INIT also fires on resize and when returning to an open
        // screen. Those paths only call repositionElements, which keeps the
        // existing widgets, so move our earlier button instead of adding a
        // second one. A full rebuild clears the list and we add a fresh one.
        val existing = added[screen]
        if (existing != null && existing in widgets) {
            existing.setPosition(button.x, button.y)
            return
        }

        widgets.add(button)
        added[screen] = button
    }
}
