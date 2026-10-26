package be.verdonckit.zapper.plugins.vtmgo

import org.json.JSONArray
import org.json.JSONObject

// Kleine helpers die zich op Android én op de JVM hetzelfde gedragen.
// (Android's optString geeft "null" terug voor JSON null; dat willen we nooit.)

internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.path(vararg keys: String): JSONObject? {
    var cur: JSONObject? = this
    for (k in keys) cur = cur?.optJSONObject(k)
    return cur
}

/** JSON-string escapen zonder afhankelijk te zijn van key-volgorde van JSONObject. */
internal fun jsonString(s: String): String = JSONObject.quote(s)
