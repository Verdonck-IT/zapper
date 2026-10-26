package be.verdonckit.zapper.plugin

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * index.json zoals CI hem publiceert (zie tools/publish/publish_plugin.py).
 * Het hele bestand is gesigneerd (index.json.sig), de jars zelf via hun sha256 in de index.
 * Een zender kan meerdere entries hebben: de laatste build per plugin-API.
 */
data class PluginEntry(
    val id: String,
    val name: String,
    val build: Int,
    val version: String,
    val apiVersion: Int,
    val entryClass: String,
    val file: String,
    val sha256: String,
)

/**
 * Wat de index betekent voor deze app (de "shell"). De app laadt enkel plugins met exact
 * dezelfde plugin-API ([be.verdonckit.zapper.api.PROVIDER_API_VERSION]); de index bevat per
 * zender de laatste build per API-versie, zodat oudere apps hun laatste passende build houden.
 */
data class Compatibility(
    /** Plugin-API van deze app. */
    val appApi: Int,
    /** Hoogste plugin-API die de nieuwste builds vragen, als die hoger is dan [appApi]: app-update verplicht. */
    val requiredApi: Int?,
    /** Zenders waarvan de nieuwste build een nieuwere app vraagt. */
    val needsNewerApp: List<String>,
    /** Zenders zonder enige build voor deze app (de app is nieuwer dan de gepubliceerde plugins). */
    val awaitingPlugins: List<String>,
    val checkedAt: Long,
) {
    val updateRequired: Boolean get() = requiredApi != null
}

object PluginCompat {
    /** Per zender de nieuwste build die deze app kan laden. */
    fun installable(entries: List<PluginEntry>, appApi: Int): List<PluginEntry> =
        entries.filter { it.apiVersion == appApi }.groupBy { it.id }.map { (_, builds) -> builds.maxBy { it.build } }

    fun evaluate(entries: List<PluginEntry>, appApi: Int, now: Long): Compatibility {
        val needsNewer = mutableListOf<PluginEntry>()
        val awaiting = mutableListOf<PluginEntry>()
        entries.groupBy { it.id }.forEach { (_, builds) ->
            val newest = builds.maxBy { it.build }
            when {
                newest.apiVersion > appApi -> needsNewer += newest
                builds.none { it.apiVersion == appApi } -> awaiting += newest
            }
        }
        return Compatibility(
            appApi = appApi,
            requiredApi = needsNewer.maxOfOrNull { it.apiVersion },
            needsNewerApp = needsNewer.map { it.name }.sorted(),
            awaitingPlugins = awaiting.map { it.name }.sorted(),
            checkedAt = now,
        )
    }
}

object PluginIndex {
    fun parse(json: String): List<PluginEntry> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("plugins")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PluginEntry(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                build = o.getInt("build"),
                version = o.getString("version"),
                apiVersion = o.getInt("apiVersion"),
                entryClass = o.getString("entryClass"),
                file = o.getString("file"),
                sha256 = o.getString("sha256").lowercase(),
            )
        }
    }
}

object PluginVerifier {
    /** ECDSA P-256 / SHA-256, handtekening in DER, base64. Werkt op elke Android-versie. */
    fun verifyIndex(indexBytes: ByteArray, signatureB64: String, publicKeyB64: String): Boolean = runCatching {
        require(publicKeyB64.isNotBlank()) { "geen publieke plugin-sleutel geconfigureerd" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(indexBytes)
            verify(Base64.decode(signatureB64.trim(), Base64.DEFAULT))
        }
    }.getOrDefault(false)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
