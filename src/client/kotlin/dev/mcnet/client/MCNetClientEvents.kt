package dev.mcnet.client

import computer.iroh.EndpointAddr
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory

/**
 * Client-side lifecycle events for addons. Both fire on the render thread.
 */
object MCNetClientEvents {
    fun interface Joined {
        /**
         * A tunnel to [host] is up and Minecraft is about to connect through
         * it. The host's endpoint id is `host.id()`.
         */
        fun onJoined(host: EndpointAddr)
    }

    fun interface Left {
        /** The tunnel was torn down, because the player left or the join failed. */
        fun onLeft()
    }

    @JvmField
    val JOINED: Event<Joined> =
        EventFactory.createArrayBacked(Joined::class.java) { listeners ->
            Joined { host -> listeners.forEach { it.onJoined(host) } }
        }

    @JvmField
    val LEFT: Event<Left> =
        EventFactory.createArrayBacked(Left::class.java) { listeners ->
            Left { listeners.forEach { it.onLeft() } }
        }
}
