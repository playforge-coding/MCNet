package dev.mcnet

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import net.minecraft.server.permissions.Permissions
import java.io.IOException
import java.net.ServerSocket

/**
 * `/mcnet` — the hosting side, available in singleplayer (through the
 * integrated server) and on dedicated servers alike.
 */
object HostCommands {

    fun build(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("mcnet")
            .then(
                Commands.literal("host")
                    // Exposing a server to the internet is an admin decision.
                    // Singleplayer hosts use the pause-menu button instead,
                    // which avoids requiring cheats just to open a world.
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                    .executes(::host),
            )
            .then(
                Commands.literal("stop")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                    .executes(::stop),
            )
            .then(Commands.literal("status").executes(::status))

    private fun host(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val server = source.server

        if (MCNet.isHosting) {
            source.sendFailure(Component.literal("MCNet is already hosting. Use /mcnet status."))
            return 0
        }

        source.sendSuccess({ Component.literal("Starting MCNet…") }, false)

        MCNet.startHosting(server) { result ->
            // Hop back onto the server thread before touching command output.
            server.execute {
                result.fold(
                    onSuccess = { codes -> announce(source, codes) },
                    onFailure = { error ->
                        MCNet.logger.error("could not start hosting", error)
                        source.sendFailure(
                            Component.literal("MCNet could not start: ${error.message ?: error::class.simpleName}"),
                        )
                    },
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun announce(source: CommandSourceStack, codes: MCNet.JoinCodes) {
        source.sendSuccess({
            Component.literal("MCNet is live. Friends can join with:")
                .withStyle(ChatFormatting.GREEN)
        }, false)
        source.sendSuccess({ copyable(codes.endpointId, "Join code (permanent)") }, false)
        source.sendSuccess({ copyable(codes.ticket, "Ticket (includes current addresses)") }, false)
        source.sendSuccess({
            Component.literal("Anyone with the join code can connect — share it like a party invite.")
                .withStyle(ChatFormatting.GRAY)
        }, false)
    }

    /** Renders a code the player can click to copy to their clipboard. */
    private fun copyable(value: String, label: String): Component =
        Component.literal("$label: ")
            .withStyle(ChatFormatting.GRAY)
            .append(
                Component.literal(value).withStyle(
                    Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(ClickEvent.CopyToClipboard(value))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to copy"))),
                ),
            )

    private fun stop(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        if (!MCNet.isHosting) {
            context.source.sendFailure(Component.literal("MCNet is not hosting."))
            return 0
        }
        MCNet.stopHosting()
        context.source.sendSuccess({ Component.literal("MCNet hosting stopped.") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun status(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val tunnel = MCNet.host
        if (tunnel == null || !tunnel.isRunning) {
            source.sendSuccess({ Component.literal("MCNet is not hosting.") }, false)
            return Command.SINGLE_SUCCESS
        }
        source.sendSuccess({
            Component.literal("MCNet is hosting — ${tunnel.peerCount} peer(s) connected.")
                .withStyle(ChatFormatting.GREEN)
        }, false)
        return Command.SINGLE_SUCCESS
    }

    /**
     * Makes sure the server is actually listening on a TCP port, and returns it.
     *
     * A dedicated server always is. An integrated server only starts its
     * listener when the world is opened to LAN, so we do that here — MCNet
     * then dials it over loopback like any other LAN client.
     *
     * Must be called on the server thread.
     */
    fun ensurePublished(server: MinecraftServer): Int {
        if (server.isPublished) return server.port

        val port = findFreePort()
        val published = server.publishServer(
            MinecraftServer.MultiplayerScope.LAN,
            server.defaultGameType,
            false,
            port,
        )
        if (!published) {
            throw IllegalStateException("Minecraft refused to open this world to LAN on port $port")
        }
        return port
    }

    /**
     * Asks the OS for a free port by binding one and immediately releasing it.
     *
     * There is an unavoidable race between closing this socket and Minecraft
     * binding the same port, but it is the same approach the vanilla
     * "Open to LAN" screen uses, and a collision surfaces as a clean failure
     * from publishServer rather than something subtle.
     */
    private fun findFreePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: IOException) {
        throw IllegalStateException("no free port available for the local Minecraft listener", e)
    }
}
