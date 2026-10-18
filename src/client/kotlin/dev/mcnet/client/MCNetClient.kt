package dev.mcnet.client

import dev.mcnet.MCNet
import dev.mcnet.PeerStore
import dev.mcnet.net.ClientTunnel
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

object MCNetClient : ClientModInitializer {
    /**
     * The tunnel backing the session the player is currently in, if they joined
     * through MCNet. Held so it can be torn down on disconnect rather than
     * leaking a listening socket and a QUIC connection for the rest of the
     * session.
     */
    var activeTunnel: ClientTunnel? = null

    override fun onInitializeClient() {
        PeerStore.load()
        MCNetScreens.register()

        // Leaving a world must release the local listener and the QUIC
        // connection; otherwise every join leaks one of each for the rest of
        // the session.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> closeTunnel() }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            closeTunnel()
            MCNet.shutdown()
        }

        MCNet.logger.info("MCNet client ready")
    }

    fun closeTunnel() {
        val tunnel = activeTunnel ?: return
        tunnel.stop()
        activeTunnel = null
        MCNetClientEvents.LEFT.invoker().onLeft()
    }
}
