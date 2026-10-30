package dev.mcnet.voice.client

import com.mojang.blaze3d.platform.InputConstants
import dev.mcnet.MCNetConfig
import dev.mcnet.net.CompositeLookup
import dev.mcnet.net.IrohRuntime
import dev.mcnet.voice.MCNetVoice
import dev.mcnet.voice.VoiceLog
import dev.mcnet.voice.VoicePayloads
import dev.mcnet.voice.VoiceServer
import dev.mcnet.voice.client.gui.VoiceSettingsScreen
import dev.mcnet.voice.client.video.VideoNative
import kotlinx.coroutines.launch
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Client-side glue: reacts to the server's voice offer, owns the current
 * [VoiceSession], and wires up keys, HUDs and the settings button.
 */
object MCNetVoiceClient : ClientModInitializer {
    /** The call this client is in, if any. Read from the render and audio threads. */
    @Volatile var session: VoiceSession? = null
        private set

    /**
     * The last offer from the current server. Kept so that turning voice on
     * in the settings mid-session can connect without a rejoin.
     */
    private var pendingOffer: VoicePayloads.Available? = null
    private var offerGeneration = 0

    private lateinit var pushToTalk: KeyMapping
    private lateinit var toggleMute: KeyMapping
    private lateinit var toggleCamera: KeyMapping
    private lateinit var openSettings: KeyMapping

    override fun onInitializeClient() {
        VoiceClientConfig.load()

        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(VoiceLog.MOD_ID, "main"))
        pushToTalk =
            KeyMappingHelper.registerKeyMapping(
                KeyMapping("key.mcnet-voice.push_to_talk", InputConstants.KEY_V, category),
            )
        toggleMute =
            KeyMappingHelper.registerKeyMapping(
                KeyMapping("key.mcnet-voice.toggle_mute", InputConstants.KEY_M, category),
            )
        // Unbound on purpose: a camera should never come on from a key
        // pressed by accident.
        toggleCamera =
            KeyMappingHelper.registerKeyMapping(
                KeyMapping("key.mcnet-voice.toggle_camera", InputConstants.UNKNOWN.value, category),
            )
        openSettings =
            KeyMappingHelper.registerKeyMapping(
                KeyMapping("key.mcnet-voice.settings", InputConstants.UNKNOWN.value, category),
            )

        ClientPlayNetworking.registerGlobalReceiver(VoicePayloads.Available.TYPE) { payload, _ -> onOffer(payload) }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            pendingOffer = null
            endSession()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { endSession() }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        // Once a frame, with the camera already settled for it: where voices
        // should come from this frame.
        LevelExtractionEvents.END_EXTRACTION.register { context ->
            session?.updateSpatial(
                context.camera(),
                context.level(),
                context.deltaTracker().getGameTimeDeltaPartialTick(true),
            )
        }

        VoiceHud.register()
        VideoHud.register()

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is PauseScreen && screen.showsPauseMenu()) addSettingsButton(screen)
        }

        VoiceLog.logger.info(
            "MCNet Voice client ready; video {}",
            if (VideoNative.available) "available" else "unavailable (${VideoNative.unavailableReason})",
        )
    }

    /** Runs on the render thread. */
    private fun onOffer(offer: VoicePayloads.Available) {
        pendingOffer = offer
        if (VoiceClientConfig.enabled) connect(offer)
    }

    /** Called from the settings screen. */
    fun setEnabled(enabled: Boolean) {
        VoiceClientConfig.enabled = enabled
        VoiceClientConfig.save()
        if (enabled) pendingOffer?.let(::connect) else endSession()
    }

    /**
     * Resolves the offered ticket and starts a session. The host's own client
     * recognizes its own endpoint id in the ticket and attaches in-process
     * instead of dialing, since dialing yourself can never work.
     */
    private fun connect(offer: VoicePayloads.Available) {
        endSession()
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val me = VoiceServer.Player(player.uuid, player.gameProfile.name)
        val generation = ++offerGeneration

        IrohRuntime.scope.launch {
            val host =
                try {
                    CompositeLookup.DEFAULT.resolve(offer.ticket)
                } catch (e: Exception) {
                    VoiceLog.logger.warn("could not resolve the voice ticket", e)
                    return@launch
                }
            val key = MCNetConfig.secretKey()
            val myId = key.`public`().toString()
            val hostIsMe = host.id().toString() == myId

            minecraft.execute {
                // A newer offer or a disconnect may have arrived meanwhile.
                if (generation != offerGeneration || minecraft.player == null) return@execute
                session =
                    if (hostIsMe) {
                        VoiceSession.startLocal(MCNetVoice.server, me)
                    } else {
                        // Prove we hold the key before the relay sees us dial.
                        val signature = key.sign(offer.nonce).toBytes()
                        ClientPlayNetworking.send(VoicePayloads.Hello(myId, signature))
                        VoiceSession.startRemote(me.uuid, host)
                    }
            }
        }
    }

    private fun endSession() {
        offerGeneration++
        session?.close()
        session = null
    }

    private fun tick() {
        while (toggleMute.consumeClick()) {
            VoiceClientConfig.micMuted = !VoiceClientConfig.micMuted
            VoiceClientConfig.save()
        }
        while (toggleCamera.consumeClick()) {
            session?.let { it.setCamera(it.camera == null) }
        }
        while (openSettings.consumeClick()) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.gui.screen() == null) minecraft.gui.setScreen(VoiceSettingsScreen(PauseScreen(false)))
        }
        session?.microphone?.pttDown = pushToTalk.isDown
    }

    private fun addSettingsButton(screen: PauseScreen) {
        val button =
            Button
                .builder(Component.translatable("mcnet-voice.button.settings")) {
                    Minecraft.getInstance().gui.setScreen(VoiceSettingsScreen(screen))
                }.bounds(MARGIN, MARGIN + BUTTON_HEIGHT + MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build()
        Screens.getWidgets(screen).add(button)
    }

    private const val BUTTON_WIDTH = 90
    private const val BUTTON_HEIGHT = 20
    private const val MARGIN = 6
}
