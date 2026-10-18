package dev.mcnet.voice.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mcnet.voice.VoiceLog
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player-side voice settings, in `config/mcnet-voice.json`.
 *
 * Every field is read from the audio threads as well as the render thread,
 * hence the volatiles and concurrent maps. Writes come from the settings
 * screen only.
 */
object VoiceClientConfig {
    enum class Activation {
        PUSH_TO_TALK,
        VOICE,
        ;

        companion object {
            fun parse(raw: String?): Activation =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PUSH_TO_TALK
        }
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path get() = FabricLoader.getInstance().configDir.resolve("mcnet-voice.json")

    @Volatile var enabled: Boolean = true

    @Volatile var activation: Activation = Activation.PUSH_TO_TALK

    /** Voice activation opens the mic when the level in dBFS rises above this. */
    @Volatile var thresholdDb: Double = -40.0

    /** OpenAL device names. Empty means the system default. */
    @Volatile var microphone: String = ""

    @Volatile var speaker: String = ""

    /** Linear multiplier applied to captured samples. */
    @Volatile var microphoneGain: Double = 1.0

    /** Linear multiplier applied to every incoming voice. */
    @Volatile var volume: Double = 1.0

    @Volatile var micMuted: Boolean = false

    @Volatile var hud: Boolean = true

    private val mutedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val playerVolumes = ConcurrentHashMap<UUID, Double>()

    fun isMuted(uuid: UUID): Boolean = uuid in mutedPlayers

    fun setMuted(
        uuid: UUID,
        muted: Boolean,
    ) {
        if (muted) mutedPlayers += uuid else mutedPlayers -= uuid
        save()
    }

    fun volumeOf(uuid: UUID): Double = playerVolumes[uuid] ?: 1.0

    fun setVolume(
        uuid: UUID,
        volume: Double,
    ) {
        if (volume == 1.0) playerVolumes.remove(uuid) else playerVolumes[uuid] = volume
        save()
    }

    fun load() {
        val file = path
        if (!Files.exists(file)) {
            save()
            return
        }
        runCatching {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            enabled = root.get("enabled")?.asBoolean ?: true
            activation = Activation.parse(root.get("activation")?.asString)
            thresholdDb = root.get("thresholdDb")?.asDouble ?: -40.0
            microphone = root.get("microphone")?.asString ?: ""
            speaker = root.get("speaker")?.asString ?: ""
            microphoneGain = root.get("microphoneGain")?.asDouble ?: 1.0
            volume = root.get("volume")?.asDouble ?: 1.0
            micMuted = root.get("micMuted")?.asBoolean ?: false
            hud = root.get("hud")?.asBoolean ?: true
            mutedPlayers.clear()
            root.getAsJsonArray("mutedPlayers")?.forEach { element ->
                runCatching { UUID.fromString(element.asString) }.onSuccess { mutedPlayers += it }
            }
            playerVolumes.clear()
            root.getAsJsonObject("playerVolumes")?.entrySet()?.forEach { (key, value) ->
                runCatching { UUID.fromString(key) }.onSuccess { playerVolumes[it] = value.asDouble }
            }
        }.onFailure {
            VoiceLog.logger.warn("could not read {}; falling back to defaults", file, it)
        }
    }

    fun save() {
        val root =
            JsonObject().apply {
                addProperty("enabled", enabled)
                addProperty("activation", activation.name.lowercase())
                addProperty("thresholdDb", thresholdDb)
                addProperty("microphone", microphone)
                addProperty("speaker", speaker)
                addProperty("microphoneGain", microphoneGain)
                addProperty("volume", volume)
                addProperty("micMuted", micMuted)
                addProperty("hud", hud)
                add("mutedPlayers", JsonArray().apply { mutedPlayers.forEach { add(it.toString()) } })
                add(
                    "playerVolumes",
                    JsonObject().apply {
                        playerVolumes.forEach { (uuid, volume) ->
                            addProperty(uuid.toString(), volume)
                        }
                    },
                )
            }
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(root))
        }.onFailure {
            VoiceLog.logger.warn("could not write {}", path, it)
        }
    }
}
