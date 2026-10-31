package dev.mcnet.voice

import computer.iroh.EndpointId
import computer.iroh.Signature
import dev.mcnet.MCNet
import dev.mcnet.MCNetEvents
import dev.mcnet.net.IrohRuntime
import dev.mcnet.net.Protocols
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side glue between the game and [VoiceServer]. Runs on the integrated
 * server when a singleplayer world is hosted and on dedicated servers alike.
 *
 * The flow for each player:
 *
 * 1. They join the game (or hosting starts while they are already in). If
 *    their client has the addon, the server offers voice with a ticket and a
 *    nonce.
 * 2. The client signs the nonce with its iroh key and replies with its
 *    endpoint id. The server verifies the signature and binds the id to the
 *    player.
 * 3. The client dials the ticket over [VOICE_ALPN]. The core routes that
 *    connection to [VoiceServer.accept], which admits it because of the binding.
 *
 * Proximity needs the relay to know where everyone is. The relay runs on
 * network threads and must not touch [ServerPlayer], so every server tick
 * copies each player's eye position into [positions], and the relay reads
 * from that.
 */
object MCNetVoice : ModInitializer {
    private val positions = ConcurrentHashMap<UUID, VoiceServer.Position>()

    /** The relay. One per process; idle until someone is offered voice. */
    val server = VoiceServer(IrohRuntime.scope) { uuid -> positions[uuid] }

    private val random = SecureRandom()
    private val nonces = ConcurrentHashMap<UUID, ByteArray>()

    @Volatile
    private var minecraftServer: MinecraftServer? = null

    override fun onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(VoicePayloads.Available.TYPE, VoicePayloads.Available.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(VoicePayloads.Hello.TYPE, VoicePayloads.Hello.CODEC)

        Protocols.register(VOICE_ALPN) { connection -> server.accept(connection) }

        ServerPlayNetworking.registerGlobalReceiver(VoicePayloads.Hello.TYPE) { payload, context ->
            onHello(context.player(), payload)
        }

        ServerLifecycleEvents.SERVER_STARTING.register {
            minecraftServer = it
            // Read per world, so an edit applies to the next world opened
            // without restarting the game.
            VoiceServerConfig.load()
            server.range = VoiceServerConfig.effectiveRange
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            minecraftServer = null
            nonces.clear()
            positions.clear()
            server.stop()
        }
        ServerTickEvents.END_SERVER_TICK.register(::snapshotPositions)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            if (MCNet.isHosting) offer(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val uuid = handler.player.uuid
            nonces.remove(uuid)
            positions.remove(uuid)
            server.playerLeft(uuid)
        }

        // Hosting can start after players are already in (the host clicks
        // "Start hosting" mid-session), so offer to everyone present then.
        MCNetEvents.HOSTING_STARTED.register { _ ->
            val mc = minecraftServer ?: return@register
            mc.execute { mc.playerList.players.forEach { offer(it) } }
        }
        MCNetEvents.HOSTING_STOPPED.register { server.stop() }

        VoiceLog.logger.info("MCNet Voice ready")
    }

    /**
     * Server thread, once a tick. Nothing to do while everyone hears everyone
     * anyway. It runs even while nobody is in the call, so the snapshot is
     * never stale when someone joins or hosting starts again.
     */
    private fun snapshotPositions(mc: MinecraftServer) {
        if (server.range <= 0.0) return
        for (player in mc.playerList.players) {
            val eye = player.eyePosition
            positions[player.uuid] =
                VoiceServer.Position(
                    player
                        .level()
                        .dimension()
                        .identifier()
                        .toString(),
                    eye.x,
                    eye.y,
                    eye.z,
                )
        }
    }

    /**
     * Offers voice to one player. Must be called on the server thread. Does
     * nothing for a client that lacks the addon.
     */
    private fun offer(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, VoicePayloads.Available.TYPE)) return
        val uuid = player.uuid
        IrohRuntime.scope.launch {
            val ticket =
                runCatching { IrohRuntime.ticket() }
                    .onFailure { VoiceLog.logger.warn("could not mint a voice ticket", it) }
                    .getOrNull() ?: return@launch
            val nonce = ByteArray(32).also(random::nextBytes)
            val mc = minecraftServer ?: return@launch
            mc.execute {
                // Still online? A player can leave during the await above.
                if (mc.playerList.getPlayer(uuid) !== player) return@execute
                nonces[uuid] = nonce
                ServerPlayNetworking.send(player, VoicePayloads.Available(ticket, nonce))
            }
        }
    }

    /** Runs on the server thread. */
    private fun onHello(
        player: ServerPlayer,
        hello: VoicePayloads.Hello,
    ) {
        val nonce = nonces.remove(player.uuid)
        if (nonce == null) {
            VoiceLog.logger.warn("{} sent a voice hello without an offer; ignoring", player.gameProfile.name)
            return
        }
        val verified =
            runCatching {
                EndpointId.fromString(hello.endpointId).verify(nonce, Signature.fromBytes(hello.signature))
            }.isSuccess
        if (!verified) {
            VoiceLog.logger.warn(
                "{} could not prove it holds endpoint {}; ignoring",
                player.gameProfile.name,
                hello.endpointId,
            )
            return
        }
        server.bind(hello.endpointId, VoiceServer.Player(player.uuid, player.gameProfile.name))
    }
}
