package dev.mcnet.client

import dev.mcnet.MCNet
import dev.mcnet.PeerStore
import dev.mcnet.net.ClientTunnel
import dev.mcnet.net.CompositeLookup
import dev.mcnet.net.IrohRuntime
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import java.net.InetSocketAddress

/**
 * The one path from "a join code" to "Minecraft is connecting".
 *
 * Shared by the paste-a-code screen and the saved peer list so the two cannot
 * drift apart. In particular, both record the peer and both tear down a
 * previous tunnel before starting a new one.
 */
object MCNetJoin {
    /**
     * Resolves [code], opens a tunnel, and hands the result to Minecraft.
     *
     * Everything up to the hand-off happens off the render thread; [onFailure]
     * is invoked on the render thread with a human-readable reason.
     */
    fun start(
        code: String,
        parent: Screen,
        displayName: String? = null,
        onFailure: (String) -> Unit,
    ) {
        IrohRuntime.scope.launch {
            try {
                val addr = CompositeLookup.DEFAULT.resolve(code.trim())
                val tunnel = ClientTunnel(addr, IrohRuntime.scope)
                val local = tunnel.start()
                val id = addr.id().toString()
                val shortId = addr.id().fmtShort()

                Minecraft.getInstance().execute {
                    MCNetClient.closeTunnel()
                    MCNetClient.activeTunnel = tunnel
                    // Recorded once the tunnel is up: the host resolved and
                    // accepted us, which is what makes the entry worth keeping.
                    PeerStore.remember(id, displayName)
                    MCNetClientEvents.JOINED.invoker().onJoined(addr)
                    handOff(parent, shortId, local)
                }
            } catch (e: Exception) {
                MCNet.logger.warn("MCNet join failed", e)
                Minecraft.getInstance().execute { onFailure(describe(e)) }
            }
        }
    }

    private fun handOff(
        parent: Screen,
        shortId: String,
        local: InetSocketAddress,
    ) {
        val minecraft = Minecraft.getInstance()
        val data =
            ServerData(
                "MCNet: $shortId",
                "${local.hostString}:${local.port}",
                // OTHER rather than LAN: this is not a LAN-discovered server, and
                // marking it LAN would change how the client treats the entry.
                ServerData.Type.OTHER,
            )
        ConnectScreen.startConnecting(
            parent,
            minecraft,
            ServerAddress(local.hostString, local.port),
            data,
            false,
            null,
        )
    }

    /**
     * Renders a failure in a way that actually says what went wrong.
     *
     * Several of the exceptions that reach here carry a null message, so
     * showing `message` alone degrades to a generic string that tells the
     * player nothing. The type name is always present and is usually the
     * informative half.
     */
    fun describe(e: Exception): String {
        val type = e::class.simpleName ?: "Error"
        val detail = e.message?.takeIf { it.isNotBlank() }
        return if (detail != null) "$type: $detail" else "$type (see latest.log for details)"
    }
}
