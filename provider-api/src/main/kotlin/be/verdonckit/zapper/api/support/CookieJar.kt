package be.verdonckit.zapper.api.support

import java.net.HttpCookie
import java.net.URI

/**
 * Eenvoudige, thread-safe cookie-jar die zowel de Android-host als de JVM-testkit
 * gebruiken, zodat cookiegedrag in tests identiek is aan dat op de TV.
 */
class SimpleCookieJar {

    private data class Key(val domain: String, val path: String, val name: String)

    private data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val hostOnly: Boolean,
        val path: String,
        val secure: Boolean,
        val expiresAtMillis: Long?,
    )

    private val cookies = LinkedHashMap<Key, Stored>()

    @Synchronized
    fun saveFromResponse(url: String, setCookieHeaders: List<String>, nowMillis: Long = System.currentTimeMillis()) {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return
        for (header in setCookieHeaders) {
            val parsed = runCatching { HttpCookie.parse("Set-Cookie: $header") }.getOrNull() ?: continue
            for (c in parsed) {
                val explicitDomain = c.domain?.trimStart('.')?.lowercase()
                if (explicitDomain != null && !domainMatches(host, explicitDomain)) continue
                val domain = explicitDomain ?: host
                val path = c.path ?: defaultPath(uri)
                val key = Key(domain, path, c.name)
                val maxAge = c.maxAge
                if (maxAge == 0L || c.value.isNullOrEmpty() || c.value == "deleted") {
                    cookies.remove(key)
                    continue
                }
                cookies[key] = Stored(
                    name = c.name,
                    value = c.value,
                    domain = domain,
                    hostOnly = explicitDomain == null,
                    path = path,
                    secure = c.secure,
                    expiresAtMillis = if (maxAge > 0) nowMillis + maxAge * 1000 else null,
                )
            }
        }
    }

    @Synchronized
    fun cookiesFor(url: String, nowMillis: Long = System.currentTimeMillis()): Map<String, String> {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return emptyMap()
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val https = uri.scheme.equals("https", ignoreCase = true)
        cookies.values.removeAll { it.expiresAtMillis != null && it.expiresAtMillis < nowMillis }
        return cookies.values
            .filter { c ->
                val domainOk = if (c.hostOnly) host == c.domain else domainMatches(host, c.domain)
                domainOk && path.startsWith(c.path) && (!c.secure || https)
            }
            .sortedByDescending { it.path.length }
            .associate { it.name to it.value }
    }

    /** Bouwt de Cookie-header: jar + expliciete cookies, expliciet wint. */
    fun cookieHeader(url: String, explicitHeader: String?): String? {
        val merged = LinkedHashMap(cookiesFor(url))
        explicitHeader?.split(';')?.forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) merged[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun domainMatches(host: String, domain: String) =
        host == domain || host.endsWith(".$domain")

    private fun defaultPath(uri: URI): String {
        val p = uri.rawPath ?: return "/"
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }
}

/** Parse Set-Cookie headers naar naam → (waarde, maxAge in seconden of -1). */
fun parseSetCookies(headers: List<String>): Map<String, Pair<String, Long>> {
    val result = LinkedHashMap<String, Pair<String, Long>>()
    for (h in headers) {
        val parsed = runCatching { HttpCookie.parse("Set-Cookie: $h") }.getOrNull() ?: continue
        for (c in parsed) result[c.name] = c.value to c.maxAge
    }
    return result
}
