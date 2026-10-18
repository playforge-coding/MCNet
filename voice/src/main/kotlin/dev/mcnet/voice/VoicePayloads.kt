package dev.mcnet.voice

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The only two Minecraft packets voice needs, both carried as Fabric custom
 * payloads over the game connection.
 *
 * They exist to tie a game session to an iroh endpoint id. The audio itself
 * never touches the Minecraft connection.
 */
object VoicePayloads {
    /**
     * Server to client: voice is on, dial the endpoint in [ticket].
     *
     * [nonce] is fresh per offer. The client signs it with its endpoint key so
     * the server knows the id it is about to associate with this player is
     * one the player actually holds.
     */
    data class Available(
        val ticket: String,
        val nonce: ByteArray,
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Available> = TYPE

        companion object {
            val TYPE: CustomPacketPayload.Type<Available> =
                CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "available"))

            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Available> =
                StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    Available::ticket,
                    ByteBufCodecs.BYTE_ARRAY,
                    Available::nonce,
                    ::Available,
                )
        }
    }

    /** Client to server: my endpoint id, and the offer's nonce signed by it. */
    data class Hello(
        val endpointId: String,
        val signature: ByteArray,
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Hello> = TYPE

        companion object {
            val TYPE: CustomPacketPayload.Type<Hello> =
                CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "hello"))

            val CODEC: StreamCodec<RegistryFriendlyByteBuf, Hello> =
                StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    Hello::endpointId,
                    ByteBufCodecs.BYTE_ARRAY,
                    Hello::signature,
                    ::Hello,
                )
        }
    }
}
