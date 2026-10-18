package dev.mcnet.client.gui

import dev.mcnet.PeerStore
import dev.mcnet.client.MCNetJoin
import dev.mcnet.net.IrohRuntime
import dev.mcnet.net.PeerProbe
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The worlds this client has joined over MCNet, with live reachability and
 * one-click rejoin.
 *
 * Deliberately built the same way the vanilla server list is: a
 * [HeaderAndFooterLayout] wrapping an [ObjectSelectionList]. That way it
 * inherits scrolling, keyboard navigation, narration and selection behavior
 * instead of approximating them.
 */
class PeerListScreen(
    private val parent: Screen,
) : Screen(Component.translatable("mcnet.peers.title")) {
    private val layout = HeaderAndFooterLayout(this, 33, 60)
    private val status = ConcurrentHashMap<String, PeerProbe.Status>()

    private lateinit var list: PeerList
    private lateinit var joinButton: Button
    private lateinit var deleteButton: Button

    override fun init() {
        layout.addTitleHeader(title, font)

        list =
            layout.addToContents(
                PeerList(minecraft, width, layout.contentHeight, layout.headerHeight),
            )

        val footer = layout.addToFooter(LinearLayout.vertical().spacing(4))
        footer.defaultCellSetting().alignHorizontallyCenter()
        val topRow = footer.addChild(LinearLayout.horizontal().spacing(4))
        val bottomRow = footer.addChild(LinearLayout.horizontal().spacing(4))

        joinButton =
            topRow.addChild(
                Button
                    .builder(Component.translatable("mcnet.peers.join")) {
                        list.selected?.join()
                    }.width(100)
                    .build(),
            )
        topRow.addChild(
            Button
                .builder(Component.translatable("mcnet.peers.add")) {
                    minecraft.gui.setScreen(JoinScreen(this))
                }.width(100)
                .build(),
        )

        deleteButton =
            bottomRow.addChild(
                Button
                    .builder(Component.translatable("mcnet.peers.delete")) { confirmDelete() }
                    .width(74)
                    .build(),
            )
        bottomRow.addChild(
            Button
                .builder(Component.translatable("mcnet.peers.refresh")) { refreshAll() }
                .width(74)
                .build(),
        )
        bottomRow.addChild(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }.width(74).build(),
        )

        layout.visitWidgets { addRenderableWidget(it) }
        repositionElements()

        list.reload()
        onSelectionChanged()

        // Probe on first open only; afterwards the player asks explicitly, so
        // reopening the screen does not hammer the network.
        if (status.isEmpty() && PeerStore.all().isNotEmpty()) refreshAll()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        if (::list.isInitialized) list.updateSize(width, layout)
    }

    private fun onSelectionChanged() {
        val hasSelection = list.selected != null
        joinButton.active = hasSelection
        deleteButton.active = hasSelection
    }

    private fun confirmDelete() {
        val entry = list.selected ?: return
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        PeerStore.forget(entry.peer.id)
                        status.remove(entry.peer.id)
                    }
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("mcnet.peers.delete.title"),
                Component.translatable("mcnet.peers.delete.warning", entry.peer.name),
                Component.translatable("mcnet.peers.delete.confirm"),
                CommonComponents.GUI_CANCEL,
            ),
        )
    }

    private fun refreshAll() {
        val peers = PeerStore.all()
        peers.forEach { status[it.id] = PeerProbe.Status.CHECKING }

        peers.forEach { peer ->
            IrohRuntime.scope.launch {
                val online = PeerProbe.isOnline(peer.id)
                status[peer.id] =
                    if (online) PeerProbe.Status.ONLINE else PeerProbe.Status.OFFLINE
            }
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    /** The scrolling list itself. */
    inner class PeerList(
        minecraft: Minecraft,
        width: Int,
        height: Int,
        y: Int,
    ) : ObjectSelectionList<PeerList.PeerEntry>(minecraft, width, height, y, ROW_HEIGHT) {
        fun reload() {
            clearEntries()
            PeerStore.all().forEach { addEntry(PeerEntry(it)) }
        }

        override fun setSelected(entry: PeerEntry?) {
            super.setSelected(entry)
            onSelectionChanged()
        }

        inner class PeerEntry(
            val peer: PeerStore.Peer,
        ) : ObjectSelectionList.Entry<PeerEntry>() {
            override fun getNarration(): Component =
                Component.translatable("mcnet.peers.narrate", peer.name, statusText())

            override fun extractContent(
                graphics: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                partialTick: Float,
            ) {
                val font = this@PeerListScreen.font

                graphics.text(font, peer.name, contentX + 4, contentY + 1, WHITE)
                graphics.text(
                    font,
                    PeerStore.shorten(peer.id),
                    contentX + 4,
                    contentY + 1 + font.lineHeight + 2,
                    GRAY,
                )

                // Status sits right-aligned, mirroring where the vanilla list
                // puts ping and player counts.
                val label = statusText()
                graphics.text(
                    font,
                    label,
                    contentRight - font.width(label) - 4,
                    contentY + 1,
                    statusColor(),
                )
            }

            override fun mouseClicked(
                event: MouseButtonEvent,
                doubleClick: Boolean,
            ): Boolean {
                this@PeerList.setSelected(this)
                // Double-click to join, matching the vanilla server list.
                if (doubleClick) join()
                return true
            }

            fun join() {
                MCNetJoin.start(peer.id, parent, peer.name) { reason ->
                    // Surfacing the reason needs a screen that still exists;
                    // the join screen owns that conversation, so hand it over
                    // with the code prefilled.
                    minecraft.gui.setScreen(JoinScreen(this@PeerListScreen, peer.id, reason))
                }
            }

            private fun state() = status[peer.id] ?: PeerProbe.Status.CHECKING

            private fun statusText(): Component =
                when (state()) {
                    PeerProbe.Status.ONLINE -> Component.translatable("mcnet.peers.online")
                    PeerProbe.Status.OFFLINE -> Component.translatable("mcnet.peers.offline")
                    PeerProbe.Status.CHECKING -> Component.translatable("mcnet.peers.checking")
                }

            private fun statusColor(): Int =
                when (state()) {
                    PeerProbe.Status.ONLINE -> GREEN
                    PeerProbe.Status.OFFLINE -> RED
                    PeerProbe.Status.CHECKING -> GRAY
                }
        }
    }

    private companion object {
        const val ROW_HEIGHT = 36

        // ARGB, matching the palette the vanilla list uses for its own labels.
        const val WHITE = -1
        const val GRAY = -8355712
        val GREEN = 0xFF55FF55.toInt()
        val RED = 0xFFFF5555.toInt()
    }
}
