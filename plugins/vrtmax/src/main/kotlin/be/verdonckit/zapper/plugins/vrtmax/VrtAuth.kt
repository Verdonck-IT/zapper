package be.verdonckit.zapper.plugins.vrtmax

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.get
import be.verdonckit.zapper.api.postJson
import be.verdonckit.zapper.api.support.parseSetCookies
import be.verdonckit.zapper.plugins.vrtmax.shared.LoginGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VRT-login (OIDC via login.vrt.be) en de tokens die VRT MAX gebruikt:
 *  - access token (`vrtnu-site_profile_at`) voor de GraphQL-API
 *  - refresh token (`vrtnu-site_profile_rt`)
 *  - video token (`vrtnu-site_profile_vt`) als identity voor de player token
 *  - vrtPlayerToken voor de media-aggregator (per variant: ondemand / live)
 *
 * Volgorde bij een verlopen access token: refresh token → stil inloggen met de bewaarde
 * credentials (bewaakt door [LoginGuard]). Een netwerkfout laat de sessie ongemoeid.
 *
 * Flow afgeleid van plugin.video.vrt.nu (add-ons/plugin.video.vrt.nu, GPLv3).
 */
internal class VrtAuth(private val host: HostServices) {

    private val http get() = host.http
    private val store get() = host.storage
    private val guard by lazy { LoginGuard(host.storage, host::nowMillis) }

    /** Eén refresh of login tegelijk: parallelle calls (sync, rijen) wachten op dezelfde vernieuwing. */
    private val tokenLock = Mutex()

    // ---------- Login ----------

    /** Login die de gebruiker zelf vraagt (Instellingen, contracttest). */
    suspend fun login(creds: Credentials): LoginResult = guardedLogin(creds, explicit = true)

    private suspend fun guardedLogin(creds: Credentials, explicit: Boolean): LoginResult {
        guard.check(creds, explicit)?.let { return LoginResult.Failure(it.reason, "VRT MAX: ${it.message}") }
        return performLogin(creds).also { guard.record(creds, it) }
    }

    private suspend fun performLogin(creds: Credentials): LoginResult {
        http.clearCookies()
        try {
            val init = http.get(SSO_INIT, followRedirects = false)
            val authorize = init.header("location")
                ?: return apiChanged("SSO-init gaf geen redirect (HTTP ${init.code})")
            http.get(resolve(SSO_INIT, authorize)) // zet SESSION + OIDCXSRF in de jar

            val xsrf = http.cookies(SSO_LOGIN)["OIDCXSRF"]
                ?: return apiChanged("geen OIDCXSRF-cookie na authorize")

            val payload = "{\"clientId\":\"vrtnu-site\",\"loginID\":${jsonString(creds.username)},\"password\":${jsonString(creds.password)}}"
            val resp = http.postJson(SSO_LOGIN, payload, headers = mapOf("OIDCXSRF" to xsrf))
            // Te veel logins kort na elkaar: VRT antwoordt dan met 5xx of 429 (tijdelijk, geen API-wijziging).
            if (resp.code >= 500 || resp.code == 429) {
                guard.backOff()
                return LoginResult.Failure(
                    LoginFailure.NETWORK,
                    "VRT-login tijdelijk niet beschikbaar (HTTP ${resp.code}). Probeer over enkele minuten opnieuw.",
                )
            }
            val json = runCatching { JSONObject(resp.text()) }.getOrNull()
                ?: return apiChanged("perform_login gaf geen JSON (HTTP ${resp.code})")

            if (json.optInt("errorCode", -1) != 0) {
                val details = json.str("errorDetails") ?: json.str("errorMessage") ?: "onbekende fout"
                val reason = if (details.contains("password", true) || details.contains("loginID", true)) {
                    LoginFailure.INVALID_CREDENTIALS
                } else {
                    LoginFailure.ACCOUNT_PROBLEM
                }
                return LoginResult.Failure(reason, details)
            }

            val redirect = json.str("redirectUrl") ?: return apiChanged("geen redirectUrl in login-antwoord")

            // Zoals de Kodi-add-on: SESSION en oidcstate expliciet meesturen over domeinen heen.
            val all = http.cookies("https://login.vrt.be/") + http.cookies("https://www.vrt.be/vrtnu/")
            val cookie = listOfNotNull(
                all["SESSION"]?.let { "SESSION=$it" },
                all["oidcstate"]?.let { "oidcstate=$it" },
            ).joinToString("; ")
            val headers = if (cookie.isEmpty()) emptyMap() else mapOf("Cookie" to cookie)

            val r1 = http.get(redirect, headers, followRedirects = false)
            val next = r1.header("location") ?: return apiChanged("callback gaf geen redirect (HTTP ${r1.code})")
            val r2 = http.get(resolve(redirect, next), headers, followRedirects = false)

            if (AT !in absorbTokens(r1, r2)) return apiChanged("geen access token ontvangen na login")
            host.log(LogLevel.INFO, "VRT MAX: ingelogd met wachtwoord")
            return LoginResult.Success
        } catch (e: ProviderException) {
            throw e
        } catch (e: java.io.IOException) {
            return LoginResult.Failure(LoginFailure.NETWORK, e.message ?: "netwerkfout")
        }
    }

    fun logout() {
        listOf(AT, RT, VT, PT_ONDEMAND, PT_LIVE).forEach { store.remove(it); store.remove("$it.exp") }
        http.clearCookies()
    }

    // ---------- Tokens ----------

    suspend fun accessToken(): String = profileToken(AT)

    suspend fun videoToken(): String = profileToken(VT)

    fun invalidateAccessToken() {
        store.remove("$AT.exp")
        store.remove("$VT.exp")
    }

    private suspend fun profileToken(name: String): String =
        valid(name) ?: tokenLock.withLock { valid(name) ?: renewAndGet(name) }

    private suspend fun renewAndGet(name: String): String {
        when (refresh()) {
            Refresh.OK -> valid(name)?.let { return it }
            // Sessie is (waarschijnlijk) nog goed; niet inloggen, gewoon later opnieuw proberen.
            Refresh.UNAVAILABLE -> throw ProviderException(ErrorKind.NETWORK, "VRT MAX: tokens vernieuwen lukt nu niet (netwerk of VRT)")
            Refresh.NO_TOKEN, Refresh.REJECTED -> Unit
        }
        val creds = host.credentials()
            ?: throw ProviderException(ErrorKind.NOT_LOGGED_IN, "VRT MAX: niet ingelogd")
        when (val r = guardedLogin(creds, explicit = false)) {
            is LoginResult.Success -> return valid(name)
                ?: throw ProviderException(ErrorKind.API_CHANGED, "token $name ontbreekt na login")
            is LoginResult.Failure -> {
                host.log(LogLevel.WARN, "VRT MAX: stil inloggen mislukt (${r.reason}): ${r.message}")
                throw ProviderException(LoginGuard.errorKind(r.reason), "VRT MAX login: ${r.message}")
            }
        }
    }

    private enum class Refresh { OK, NO_TOKEN, REJECTED, UNAVAILABLE }

    private suspend fun refresh(): Refresh {
        val rt = store.get(RT) ?: return Refresh.NO_TOKEN
        // Lege jar: zo komen enkel tokens uit dít antwoord binnen, geen oude uit een eerdere login.
        http.clearCookies()
        val resp = try {
            http.get(SSO_REFRESH, headers = mapOf("Cookie" to "$RT=$rt"))
        } catch (e: java.io.IOException) {
            host.log(LogLevel.WARN, "VRT MAX: refresh niet bereikbaar (${e::class.simpleName})")
            return Refresh.UNAVAILABLE
        }
        if (resp.code >= 500 || resp.code == 429) {
            host.log(LogLevel.WARN, "VRT MAX: refresh tijdelijk niet beschikbaar (HTTP ${resp.code})")
            return Refresh.UNAVAILABLE
        }
        if ((resp.isSuccessful || resp.isRedirect) && AT in absorbTokens(resp)) {
            host.log(LogLevel.INFO, "VRT MAX: sessie vernieuwd met refresh token")
            return Refresh.OK
        }
        // 4xx of geen nieuw access token: refresh token is verlopen of ingetrokken.
        host.log(LogLevel.WARN, "VRT MAX: refresh token geweigerd (HTTP ${resp.code})")
        store.remove(RT)
        store.remove("$RT.exp")
        return Refresh.REJECTED
    }

    /** Haalt at/rt/vt uit Set-Cookie headers en valt terug op de cookie-jar. Geeft terug welke tokens binnenkwamen. */
    private fun absorbTokens(vararg responses: HttpResponse): Set<String> {
        val absorbed = HashSet<String>()
        val now = host.nowMillis()
        val fromHeaders = responses.flatMap { parseSetCookies(it.setCookieHeaders()).entries }.associate { it.key to it.value }
        val jar = http.cookies("https://www.vrt.be/vrtmax/") + http.cookies("https://www.vrt.be/vrtnu/")
        for (name in listOf(AT, RT, VT)) {
            val (value, maxAge) = fromHeaders[name] ?: jar[name]?.let { it to -1L } ?: continue
            if (value.isBlank() || value == "deleted") continue
            val ttl = when {
                maxAge > 0 -> maxAge * 1000
                name == RT -> 180L * 24 * 3600 * 1000
                else -> 55L * 60 * 1000
            }
            store.put(name, value)
            store.put("$name.exp", (now + ttl).toString())
            absorbed += name
        }
        return absorbed
    }

    // ---------- Player token ----------

    suspend fun playerToken(live: Boolean): String {
        val key = if (live) PT_LIVE else PT_ONDEMAND
        valid(key)?.let { return it }

        val body = StringBuilder("{\"playerInfo\":").append(jsonString(playerInfo()))
        if (!live) body.append(",\"identityToken\":").append(jsonString(videoToken()))
        body.append('}')

        val resp = http.postJson(PLAYERTOKEN_URL, body.toString())
        val json = runCatching { JSONObject(resp.text()) }.getOrNull()
        val token = json?.str("vrtPlayerToken")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "geen vrtPlayerToken (HTTP ${resp.code})")
        val exp = json.str("expirationDate")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: (host.nowMillis() + 30 * 60 * 1000)
        store.put(key, token)
        store.put("$key.exp", exp.toString())
        return token
    }

    /**
     * De webplayer identificeert zich met een HS256-JWT waarvan kid/secret
     * in de player-scripts zitten. We halen die eens per dag op.
     */
    private suspend fun playerInfo(): String {
        val keys = cachedPlayerKeys() ?: scrapePlayerKeys()
        val header = "{\"alg\":\"HS256\",\"kid\":${jsonString(keys.kid)}}"
        val exp = String.format(java.util.Locale.ROOT, "%.3f", host.nowMillis() / 1000.0 + 3600)
        val payload = "{\"drm\":{\"widevine\":\"L3\"},\"exp\":$exp,\"platform\":\"desktop\"," +
            "\"app\":{\"type\":\"browser\",\"name\":\"Firefox\",\"version\":\"137.0\"}," +
            "\"device\":\"undefined (undefined)\",\"os\":{\"name\":\"Linux\",\"version\":\"x86_64\"}," +
            "\"player\":{\"name\":\"VRT web player\",\"version\":${jsonString(keys.version)}}}"
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val signingInput = b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(payload.toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.secret.toByteArray(), "HmacSHA256")) }
        return signingInput + "." + b64.encodeToString(mac.doFinal(signingInput.toByteArray()))
    }

    private data class PlayerKeys(val kid: String, val secret: String, val version: String)

    private fun cachedPlayerKeys(): PlayerKeys? {
        val raw = valid(PLAYER_KEYS) ?: return null
        val parts = raw.split('\n')
        return if (parts.size == 3) PlayerKeys(parts[0], parts[1], parts[2]) else null
    }

    private suspend fun scrapePlayerKeys(): PlayerKeys {
        val folder = PLAYER_LIB.substringBeforeLast('/')
        val main = http.get(PLAYER_LIB).text()
        val seen = HashSet<String>()
        val candidates = mutableListOf<String>()

        for (m in FIRST_LEVEL.findAll(main)) {
            val url = folder + m.groupValues[1]
            if (!seen.add(url)) continue
            if ("drm" in url || "bootstrapper" in url) candidates += url
            val script = http.get(url).text()
            for (m2 in SECOND_LEVEL.findAll(script)) {
                val sub = folder + m2.groupValues[1]
                if (seen.add(sub) && ("drm" in sub || "bootstrapper" in sub)) candidates += sub
            }
        }

        var version = "5.2.2"
        var atobs: List<String> = emptyList()
        for (url in candidates) {
            val content = http.get(url).text()
            VERSION.find(content)?.let { version = it.groupValues[1] }
            ATOB.findAll(content).map { it.groupValues[1] }.toList().takeIf { it.isNotEmpty() }?.let { atobs = it }
        }
        if (atobs.isEmpty()) throw ProviderException(ErrorKind.API_CHANGED, "geen player-sleutels gevonden in player-lib.js (${candidates.size} scripts)")

        val dec = Base64.getDecoder()
        val keys = PlayerKeys(
            kid = String(dec.decode(atobs.first().reversed())),
            secret = String(dec.decode(atobs.last().reversed())),
            version = version,
        )
        store.put(PLAYER_KEYS, "${keys.kid}\n${keys.secret}\n${keys.version}")
        store.put("$PLAYER_KEYS.exp", (host.nowMillis() + 24 * 3600 * 1000).toString())
        return keys
    }

    // ---------- helpers ----------

    private fun valid(key: String): String? {
        val value = store.get(key) ?: return null
        val exp = store.get("$key.exp")?.toLongOrNull() ?: return null
        return if (exp - 60_000 > host.nowMillis()) value else null
    }

    private fun apiChanged(msg: String) = LoginResult.Failure(LoginFailure.API_CHANGED, msg)

    private fun resolve(base: String, location: String) = URI(base).resolve(location).toString()

    companion object {
        const val SSO_INIT = "https://www.vrt.be/vrtnu/sso/login?scope=openid,mid"
        const val SSO_LOGIN = "https://login.vrt.be/perform_login"
        const val SSO_REFRESH = "https://www.vrt.be/vrtnu/sso/refresh"
        const val PLAYERTOKEN_URL = "https://media-services-public.vrt.be/vualto-video-aggregator-web/rest/external/v2/tokens"
        const val PLAYER_LIB = "https://player.vrt.be/vrtmax/js/player-lib.js"

        const val AT = "vrtnu-site_profile_at"
        const val RT = "vrtnu-site_profile_rt"
        const val VT = "vrtnu-site_profile_vt"
        private const val PT_ONDEMAND = "playertoken.ondemand"
        private const val PT_LIVE = "playertoken.live"
        private const val PLAYER_KEYS = "playerkeys"

        private val FIRST_LEVEL = Regex("\"\\.(/[a-z0-9/\\-]+[a-z0-9]{8}\\.js)\";")
        private val SECOND_LEVEL = Regex("import\\(\"\\.(/[a-z0-9\\-]+\\.js)\"\\)")
        private val VERSION = Regex("\\s\"(\\d\\.\\d\\.\\d-[a-zA-Z0-9\\-:]*)\"")
        private val ATOB = Regex("atob\\(\"(==[A-Za-z0-9+/]*)\"")
    }
}
