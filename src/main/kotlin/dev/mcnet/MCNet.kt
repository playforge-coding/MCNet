package dev.mcnet

import dev.mcnet.net.HostTunnel
import dev.mcnet.net.IrohRuntime
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

object MCNet : ModInitializer {
    const val MOD_ID = "mcnet"

    @JvmField
    val logger = LoggerFactory.getLogger(MOD_ID)!!

    /** The tunnel serving this world, if hosting is active. */
    var host: HostTunnel? = null
        private set

    val isHosting: Boolean get() = host?.isRunning == true

    override fun onInitialize() {
        MCNetConfig.load()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(HostCommands.build())
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (MCNetConfig.autoHost) {
                startHosting(server) { result ->
                    result
                        .onSuccess { logger.info("auto-hosting; join code {}", it.endpointId) }
                        .onFailure { logger.error("auto-host failed", it) }
                }
            }
        }

        // Stop tunneling before the server tears down its own listener, so
        // peers see a clean disconnect rather than a hang.
        ServerLifecycleEvents.SERVER_STOPPING.register { stopHosting() }

        logger.info("MCNet ready")
    }

    /** The two forms of join code handed out once hosting starts. */
    data class JoinCodes(
        val endpointId: String,
        val ticket: String,
    )

    /**
     * Starts hosting this server.
     *
     * Opening the world to LAN has to happen on the caller's (server) thread,
     * but binding the iroh endpoint involves a relay handshake that can take
     * seconds. So the join codes arrive through [onReady] rather than as a
     * return value. [onReady] runs on a background thread.
     */
    fun startHosting(
        server: MinecraftServer,
        onReady: (Result<JoinCodes>) -> Unit,
    ) {
        if (isHosting) {
            IrohRuntime.scope.launch { onReady(currentJoinCodes()) }
            return
        }

        val port =
            try {
                HostCommands.ensurePublished(server)
            } catch (e: Exception) {
                onReady(Result.failure(e))
                return
            }

        val tunnel = HostTunnel(port, IrohRuntime.scope)
        tunnel.start()
        host = tunnel

        IrohRuntime.scope.launch {
            val codes = currentJoinCodes()
            if (codes.isFailure) {
                stopHosting()
            } else {
                // Announced once the endpoint is online, so a listener that
                // immediately asks for a ticket gets a complete one.
                MCNetEvents.HOSTING_STARTED.invoker().onHostingStarted(tunnel)
            }
            onReady(codes)
        }
    }

    private suspend fun currentJoinCodes(): Result<JoinCodes> =
        runCatching {
            // Wait for a usable network path first: a ticket minted before the
            // relay handshake finishes may not list a relay, and would then only
            // work for peers on the same LAN.
            IrohRuntime.awaitOnline()
            JoinCodes(IrohRuntime.endpointId(), IrohRuntime.ticket())
        }

    fun stopHosting() {
        val tunnel = host ?: return
        tunnel.stop()
        host = null
        MCNetEvents.HOSTING_STOPPED.invoker().onHostingStopped()
    }

    /** Called on client shutdown and dedicated-server stop. */
    fun shutdown() {
        stopHosting()
        IrohRuntime.shutdown()
    }
}
