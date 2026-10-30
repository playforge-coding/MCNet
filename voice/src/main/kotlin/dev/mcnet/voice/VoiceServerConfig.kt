package dev.mcnet.voice

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The host's side of the voice settings, in `config/mcnet-voice-server.json`.
 * Read by whoever runs the relay: a dedicated server, or the client that is
 * hosting a singleplayer world.
 *
 * Range is enforced on the relay and announced to clients, so a joiner's own
 * config has no say in how far voices carry on someone else's world.
 */
object VoiceServerConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path get() = FabricLoader.getInstance().configDir.resolve("mcnet-voice-server.json")

    /** Whether voices fade with distance at all. Off means everyone hears everyone. */
    var proximity: Boolean = true
        private set

    /** How far a voice carries, in blocks, when [proximity] is on. */
    var range: Double = DEFAULT_RANGE
        private set

    /** What the relay should enforce: the range, or zero for no proximity. */
    val effectiveRange: Double get() = if (proximity) range else 0.0

    fun load() {
        val file = path
        if (!Files.exists(file)) {
            save()
            return
        }
        runCatching {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            proximity = root.get("proximity")?.asBoolean ?: true
            range = (root.get("range")?.asDouble ?: DEFAULT_RANGE).coerceIn(MIN_RANGE, MAX_RANGE)
        }.onFailure {
            VoiceLog.logger.warn("could not read {}; falling back to defaults", file, it)
        }
    }

    fun save() {
        val root =
            JsonObject().apply {
                addProperty("proximity", proximity)
                addProperty("range", range)
            }
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(root))
        }.onFailure {
            VoiceLog.logger.warn("could not write {}", path, it)
        }
    }

    const val DEFAULT_RANGE = 48.0
    const val MIN_RANGE = 1.0

    /** Further than any client tracks other players anyway. */
    const val MAX_RANGE = 512.0
}
