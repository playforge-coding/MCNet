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
 * Player-side voice and video settings, in `config/mcnet-voice.json`.
 *
 * Every field is read from the media threads as well as the render thread,
 * hence the volatiles and concurrent maps. Writes come from the settings
 * screens only.
 *
 * Whether the camera is on is deliberately not here: a camera never comes
 * on by itself when a session starts.
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

    /** Capture height and encoder target. The width follows the camera's aspect ratio. */
    enum class CameraQuality(
        val height: Int,
        val kbps: Int,
    ) {
        LOW(240, 200),
        MEDIUM(360, 400),
        HIGH(480, 700),
        FULL(720, 1500),
        ;

        companion object {
            fun parse(raw: String?): CameraQuality =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MEDIUM
        }
    }

    /** Where other players' cameras are drawn. Your own preview is always on the HUD. */
    enum class CameraPlacement(
        val aboveHead: Boolean,
        val onHud: Boolean,
    ) {
        HEAD(aboveHead = true, onHud = false),
        HUD(aboveHead = false, onHud = true),
        BOTH(aboveHead = true, onHud = true),
        ;

        companion object {
            fun parse(raw: String?): CameraPlacement =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: HEAD
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

    /** The camera's id as the OS reports it. Empty means the first one found. */
    @Volatile var camera: String = ""

    @Volatile var cameraQuality: CameraQuality = CameraQuality.MEDIUM

    /** Whether other players' cameras are shown (and fetched) at all. */
    @Volatile var showCameras: Boolean = true

    @Volatile var cameraPlacement: CameraPlacement = CameraPlacement.HEAD

    /** Width of each camera tile on the HUD, in GUI pixels. */
    @Volatile var cameraTileWidth: Int = 128

    private val mutedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val hiddenCameras: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val playerVolumes = ConcurrentHashMap<UUID, Double>()

    fun isMuted(uuid: UUID): Boolean = uuid in mutedPlayers

    fun setMuted(
        uuid: UUID,
        muted: Boolean,
    ) {
        if (muted) mutedPlayers += uuid else mutedPlayers -= uuid
        save()
    }

    fun isCameraHidden(uuid: UUID): Boolean = uuid in hiddenCameras

    fun setCameraHidden(
        uuid: UUID,
        hidden: Boolean,
    ) {
        if (hidden) hiddenCameras += uuid else hiddenCameras -= uuid
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
            camera = root.get("camera")?.asString ?: ""
            cameraQuality = CameraQuality.parse(root.get("cameraQuality")?.asString)
            showCameras = root.get("showCameras")?.asBoolean ?: true
            cameraPlacement = CameraPlacement.parse(root.get("cameraPlacement")?.asString)
            cameraTileWidth = (root.get("cameraTileWidth")?.asInt ?: 128).coerceIn(MIN_TILE_WIDTH, MAX_TILE_WIDTH)
            mutedPlayers.clear()
            root.getAsJsonArray("mutedPlayers")?.forEach { element ->
                runCatching { UUID.fromString(element.asString) }.onSuccess { mutedPlayers += it }
            }
            hiddenCameras.clear()
            root.getAsJsonArray("hiddenCameras")?.forEach { element ->
                runCatching { UUID.fromString(element.asString) }.onSuccess { hiddenCameras += it }
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
                addProperty("camera", camera)
                addProperty("cameraQuality", cameraQuality.name.lowercase())
                addProperty("showCameras", showCameras)
                addProperty("cameraPlacement", cameraPlacement.name.lowercase())
                addProperty("cameraTileWidth", cameraTileWidth)
                add("mutedPlayers", JsonArray().apply { mutedPlayers.forEach { add(it.toString()) } })
                add("hiddenCameras", JsonArray().apply { hiddenCameras.forEach { add(it.toString()) } })
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

    const val MIN_TILE_WIDTH = 64
    const val MAX_TILE_WIDTH = 320
}
