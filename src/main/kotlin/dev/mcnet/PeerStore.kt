package dev.mcnet

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The worlds this client has joined through MCNet, remembered so they can be
 * rejoined without pasting a code again.
 *
 * Entries are keyed by endpoint id rather than by ticket. An endpoint id is
 * derived from the host's secret key and never changes, so a saved entry keeps
 * working after the host moves networks. That is exactly the property a
 * server list needs and a ticket cannot provide.
 */
object PeerStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path get() = FabricLoader.getInstance().configDir.resolve("mcnet-peers.json")

    data class Peer(
        /** The host's permanent endpoint id. Also the join code. */
        val id: String,
        /** Display name. Defaults to a shortened id until the player renames it. */
        var name: String,
        /** Epoch millis of the last successful join, or 0 if never joined. */
        var lastJoined: Long = 0,
    )

    private val peers = mutableListOf<Peer>()

    /** Snapshot of saved peers, most recently joined first. */
    fun all(): List<Peer> = peers.sortedByDescending { it.lastJoined }

    fun load() {
        peers.clear()
        val file = path
        if (!Files.exists(file)) return
        runCatching {
            JsonParser.parseString(Files.readString(file)).asJsonArray.forEach { element ->
                val o = element.asJsonObject
                val id = o.get("id")?.asString ?: return@forEach
                peers +=
                    Peer(
                        id = id,
                        name = o.get("name")?.asString ?: shorten(id),
                        lastJoined = o.get("lastJoined")?.asLong ?: 0,
                    )
            }
        }.onFailure { MCNet.logger.warn("could not read {}", file, it) }
    }

    /**
     * Records a successful join, or refreshes the timestamp if already known.
     *
     * Called after the tunnel is up rather than when the code is typed, so the
     * list only ever fills with hosts that actually worked.
     */
    fun remember(
        id: String,
        suggestedName: String? = null,
    ) {
        val existing = peers.firstOrNull { it.id == id }
        if (existing != null) {
            existing.lastJoined = System.currentTimeMillis()
        } else {
            peers += Peer(id, suggestedName ?: shorten(id), System.currentTimeMillis())
        }
        save()
    }

    fun rename(
        id: String,
        name: String,
    ) {
        peers.firstOrNull { it.id == id }?.name = name.ifBlank { shorten(id) }
        save()
    }

    fun forget(id: String) {
        peers.removeAll { it.id == id }
        save()
    }

    fun save() {
        val array =
            JsonArray().apply {
                peers.forEach { peer ->
                    add(
                        JsonObject().apply {
                            addProperty("id", peer.id)
                            addProperty("name", peer.name)
                            addProperty("lastJoined", peer.lastJoined)
                        },
                    )
                }
            }
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(array))
        }.onFailure { MCNet.logger.warn("could not write {}", path, it) }
    }

    /** Endpoint ids are 64 hex characters; nobody wants that in a list row. */
    fun shorten(id: String): String = if (id.length > 12) id.take(12) else id
}
