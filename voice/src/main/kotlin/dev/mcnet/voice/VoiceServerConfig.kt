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
        // Both settings are parsed before either is applied, so a file that
        // fails halfway can never leave proximity off by accident.
        runCatching {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            val proximity = root.get("proximity")?.asBoolean ?: true
            var range = root.get("range")?.asDouble ?: DEFAULT_RANGE
            if (!range.isFinite()) {
                // coerceIn passes NaN straight through, and the relay would
                // read a NaN range as no proximity at all.
                VoiceLog.logger.warn("{}: range {} is not a distance; using {}", file, range, DEFAULT_RANGE)
                range = DEFAULT_RANGE
            }
            this.proximity = proximity
            this.range = range.coerceIn(MIN_RANGE, MAX_RANGE)
        }.onFailure {
            proximity = true
            range = DEFAULT_RANGE
            VoiceLog.logger.warn(
                "could not read {}; using proximity on at {} blocks until it is fixed",
                file,
                DEFAULT_RANGE,
                it,
            )
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
