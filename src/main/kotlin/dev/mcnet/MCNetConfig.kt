package dev.mcnet

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import computer.iroh.SecretKey
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat

/**
 * Persisted settings, most importantly the endpoint identity.
 *
 * Keeping the secret key on disk is what makes a host's join key permanent: the
 * endpoint id is derived from it, so regenerating the key would invalidate
 * every saved server entry pointing at this host.
 */
object MCNetConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path get() = FabricLoader.getInstance().configDir.resolve("mcnet.json")

    private var cachedKey: SecretKey? = null

    /** Whether a dedicated server should start hosting as soon as it boots. */
    var autoHost: Boolean = false
        private set

    /** How a bare endpoint id gets turned into a reachable address. */
    enum class AddressLookupMode {
        /** n0's DNS resolver, shipped with the published iroh bindings. */
        N0,

        /**
         * pkarr records on the BitTorrent Mainline DHT — no dependency on n0's
         * infrastructure. Requires bindings built from `dht/apply.sh`; MCNet
         * logs a warning and falls back to [N0] if they are not present.
         */
        DHT,
        ;

        companion object {
            fun parse(raw: String?): AddressLookupMode =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: N0
        }
    }

    var addressLookup: AddressLookupMode = AddressLookupMode.N0
        private set

    /**
     * Whether to announce this endpoint's address to the DHT. Only meaningful
     * when [addressLookup] is [AddressLookupMode.DHT]. Hosts need it; a client
     * that only ever joins can turn it off to avoid pointless DHT writes.
     */
    var dhtPublish: Boolean = true
        private set

    fun load() {
        val file = path
        if (!Files.exists(file)) {
            save()
            return
        }
        runCatching {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            autoHost = root.get("autoHost")?.asBoolean ?: false
            addressLookup = AddressLookupMode.parse(root.get("addressLookup")?.asString)
            dhtPublish = root.get("dhtPublish")?.asBoolean ?: true
            root.get("secretKey")?.asString?.let { hex ->
                cachedKey = SecretKey.fromBytes(HexFormat.of().parseHex(hex))
            }
        }.onFailure {
            MCNet.logger.warn("could not read {}; falling back to defaults", file, it)
        }
    }

    /** The persistent endpoint identity, generated and stored on first use. */
    fun secretKey(): SecretKey = cachedKey ?: SecretKey.generate().also {
        cachedKey = it
        MCNet.logger.info("generated a new MCNet identity")
        save()
    }

    fun save() {
        val root = JsonObject().apply {
            addProperty("autoHost", autoHost)
            addProperty("addressLookup", addressLookup.name.lowercase())
            addProperty("dhtPublish", dhtPublish)
            cachedKey?.let { addProperty("secretKey", HexFormat.of().formatHex(it.toBytes())) }
        }
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(root))
        }.onFailure {
            MCNet.logger.warn("could not write {}", path, it)
        }
    }
}
