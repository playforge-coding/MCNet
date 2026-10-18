package dev.mcnet

import dev.mcnet.net.HostTunnel
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory

/**
 * Host-side lifecycle events for addons. Available on both the integrated and
 * the dedicated server.
 *
 * Every event fires on a background thread, never the server thread. A
 * listener that touches game state must hop over with `server.execute`.
 */
object MCNetEvents {
    fun interface HostingStarted {
        /** Hosting has begun; [tunnel] is accepting peers. The join codes may not be ready yet. */
        fun onHostingStarted(tunnel: HostTunnel)
    }

    fun interface HostingStopped {
        fun onHostingStopped()
    }

    fun interface PeerConnected {
        /** A peer with this endpoint id has tunneled into the game. */
        fun onPeerConnected(endpointId: String)
    }

    fun interface PeerDisconnected {
        fun onPeerDisconnected(endpointId: String)
    }

    @JvmField
    val HOSTING_STARTED: Event<HostingStarted> =
        EventFactory.createArrayBacked(HostingStarted::class.java) { listeners ->
            HostingStarted { tunnel -> listeners.forEach { it.onHostingStarted(tunnel) } }
        }

    @JvmField
    val HOSTING_STOPPED: Event<HostingStopped> =
        EventFactory.createArrayBacked(HostingStopped::class.java) { listeners ->
            HostingStopped { listeners.forEach { it.onHostingStopped() } }
        }

    @JvmField
    val PEER_CONNECTED: Event<PeerConnected> =
        EventFactory.createArrayBacked(PeerConnected::class.java) { listeners ->
            PeerConnected { id -> listeners.forEach { it.onPeerConnected(id) } }
        }

    @JvmField
    val PEER_DISCONNECTED: Event<PeerDisconnected> =
        EventFactory.createArrayBacked(PeerDisconnected::class.java) { listeners ->
            PeerDisconnected { id -> listeners.forEach { it.onPeerDisconnected(id) } }
        }
}
