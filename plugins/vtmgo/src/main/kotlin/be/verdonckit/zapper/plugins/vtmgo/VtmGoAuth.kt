package be.verdonckit.zapper.plugins.vtmgo

import be.verdonckit.zapper.api.DeviceLogin
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.LoginFailure
import be.verdonckit.zapper.api.LoginResult
import be.verdonckit.zapper.api.ProviderException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Login bij VTM GO (DPG Media) zoals de Android TV-app: OAuth device flow op login.vtm.be.
 * Er is geen wachtwoord: de gebruiker bevestigt een code op login.vtm.be/androidtv, daarna
 * leeft de sessie op het refresh token (dat bij elke vernieuwing wijzigt en meteen bewaard wordt).
 *
 * Flow afgeleid van de Kodi-add-on (fork frankdpGH/plugin.video.vtm.go, GPLv3).
 * Tokens staan enkel in host.storage en worden nooit gelogd.
 */
internal class VtmGoAuth(private val host: HostServices) {

    private val store get() = host.storage

    /** Eén refresh tegelijk: parallelle calls wachten op dezelfde vernieuwing (het refresh token roteert). */
    private val tokenLock = Mutex()

    // ---------- Login met code ----------

    suspend fun startDeviceLogin(): DeviceLogin {
        val json = post(AUTHORIZE_URL, mapOf("client_id" to CLIENT_ID, "scope" to SCOPE), "device/authorize")
        val deviceCode = json.str("device_code")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen device_code")
        store.put(DEVICE_CODE, deviceCode)
        return DeviceLogin(
            userCode = json.str("user_code") ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen user_code"),
            verificationUrl = json.str("verification_uri") ?: DEFAULT_VERIFICATION_URL,
            verificationUrlComplete = json.str("verification_uri_complete"),
            expiresInSeconds = json.optInt("expires_in", 300),
            pollIntervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    /** null = de gebruiker heeft de code nog niet bevestigd. */
    suspend fun pollDeviceLogin(): LoginResult? {
        val deviceCode = store.get(DEVICE_CODE)
            ?: return LoginResult.Failure(LoginFailure.UNKNOWN, "VTM GO: geen lopende login met code")
        val resp = try {
            send(TOKEN_URL, form(mapOf("device_code" to deviceCode, "client_id" to CLIENT_ID, "grant_type" to DEVICE_GRANT)))
        } catch (e: java.io.IOException) {
            return null // tijdelijk: volgende poging
        }
        val json = runCatching { JSONObject(resp.text()) }.getOrNull()
        if (resp.code == 400 || resp.code == 401) {
            return when (json?.str("error")) {
                "authorization_pending", "slow_down" -> null
                "expired_token" -> LoginResult.Failure(LoginFailure.ACCOUNT_PROBLEM, "De code is verlopen. Vraag een nieuwe code.")
                "access_denied" -> LoginResult.Failure(LoginFailure.INVALID_CREDENTIALS, "Login geweigerd op login.vtm.be.")
                else -> LoginResult.Failure(LoginFailure.API_CHANGED, "VTM GO token: ${json?.str("error") ?: "HTTP ${resp.code}"}")
            }
        }
        if (resp.code >= 500 || resp.code == 429) return null
        if (json == null || json.str("access_token") == null) {
            return LoginResult.Failure(LoginFailure.API_CHANGED, "VTM GO token: geen access_token (HTTP ${resp.code})")
        }
        store.remove(DEVICE_CODE)
        storeTokens(json)
        // Zoals de app: het id-token aanmelden bij de API, daarna het (eerste) profiel kiezen.
        json.str("id_token")?.let { idToken ->
            runCatching {
                send(API + "/VTM_GO/id-token", JSONObject().put("idToken", idToken).toString().toByteArray(), "application/json", API_HEADERS)
            }
        }
        store.remove(PROFILE)
        return try {
            profile()
            host.log(LogLevel.INFO, "VTM GO: ingelogd met code")
            LoginResult.Success
        } catch (e: ProviderException) {
            LoginResult.Failure(if (e.kind == ErrorKind.NETWORK) LoginFailure.NETWORK else LoginFailure.API_CHANGED, e.message ?: "profiel")
        }
    }

    fun logout() {
        listOf(AT, RT, EXP, PROFILE, PRODUCT, DEVICE_CODE).forEach(store::remove)
    }

    // ---------- Tokens ----------

    /** Geldig access token; vernieuwt met het refresh token als het (bijna) verlopen is. */
    suspend fun accessToken(): String = validAccessToken() ?: tokenLock.withLock { validAccessToken() ?: refresh() }

    fun invalidate() = store.remove(EXP)

    /** Het profiel (x-dpp-profile) dat de API bij elke call verwacht; het eerste profiel van het account. */
    suspend fun profile(): String {
        store.get(PROFILE)?.let { return it }
        val resp = send(API + "/VTM_GO/profiles", null, null, API_HEADERS + ("Authorization" to "Bearer ${accessToken()}"))
        if (resp.code == 401) throw ProviderException(ErrorKind.NOT_LOGGED_IN, "VTM GO profielen: HTTP 401")
        if (resp.code >= 500) throw ProviderException(ErrorKind.NETWORK, "VTM GO profielen: HTTP ${resp.code}")
        val first = runCatching { JSONObject(resp.text()).arr("profiles").objects().firstOrNull() }.getOrNull()
            ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: geen profielen (HTTP ${resp.code})")
        val id = first.str("id") ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO: profiel zonder id")
        store.put(PROFILE, id)
        first.str("product")?.let { store.put(PRODUCT, it) }
        return id
    }

    private fun validAccessToken(): String? {
        val exp = store.get(EXP)?.toLongOrNull() ?: return null
        return store.get(AT)?.takeIf { exp - 60_000 > host.nowMillis() }
    }

    private suspend fun refresh(): String {
        val rt = store.get(RT) ?: throw ProviderException(ErrorKind.NOT_LOGGED_IN, "VTM GO: niet ingelogd")
        val resp = try {
            send(TOKEN_URL, form(mapOf("refresh_token" to rt, "client_id" to CLIENT_ID, "grant_type" to "refresh_token")))
        } catch (e: java.io.IOException) {
            throw ProviderException(ErrorKind.NETWORK, "VTM GO: tokens vernieuwen lukt nu niet (${e::class.simpleName})", e)
        }
        if (resp.code >= 500 || resp.code == 429) {
            throw ProviderException(ErrorKind.NETWORK, "VTM GO: tokens vernieuwen tijdelijk niet mogelijk (HTTP ${resp.code})")
        }
        val json = runCatching { JSONObject(resp.text()) }.getOrNull()
        if (resp.code == 400 || resp.code == 401) {
            // invalid_grant: refresh token verlopen of ingetrokken. Zonder wachtwoord kan enkel een nieuwe code helpen.
            host.log(LogLevel.WARN, "VTM GO: refresh token geweigerd (${json?.str("error") ?: resp.code})")
            logout()
            throw ProviderException(ErrorKind.NOT_LOGGED_IN, "VTM GO: sessie verlopen, log opnieuw in met een code")
        }
        val at = json?.str("access_token")
            ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO refresh: geen access_token (HTTP ${resp.code})")
        storeTokens(json)
        host.log(LogLevel.INFO, "VTM GO: sessie vernieuwd met refresh token")
        return at
    }

    private fun storeTokens(json: JSONObject) {
        val at = json.str("access_token") ?: return
        store.put(AT, at)
        json.str("refresh_token")?.let { store.put(RT, it) } // roteert: altijd het nieuwste bewaren
        val expMillis = jwtExpiry(at) ?: (host.nowMillis() + json.optLong("expires_in", 3600) * 1000)
        store.put(EXP, expMillis.toString())
    }

    // ---------- HTTP ----------

    private suspend fun post(url: String, fields: Map<String, String>, what: String): JSONObject {
        val resp = try {
            send(url, form(fields))
        } catch (e: java.io.IOException) {
            throw ProviderException(ErrorKind.NETWORK, "VTM GO $what: ${e.message}", e)
        }
        if (resp.code >= 500 || resp.code == 429) throw ProviderException(ErrorKind.NETWORK, "VTM GO $what: HTTP ${resp.code}")
        return runCatching { JSONObject(resp.text()) }.getOrElse {
            throw ProviderException(ErrorKind.API_CHANGED, "VTM GO $what: geen JSON (HTTP ${resp.code})")
        }
    }

    private class Form(val body: ByteArray)

    private fun form(fields: Map<String, String>) = Form(
        fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray(),
    )

    private suspend fun send(url: String, form: Form): HttpResponse =
        send(url, form.body, "application/x-www-form-urlencoded", LOGIN_HEADERS)

    private suspend fun send(url: String, body: ByteArray?, contentType: String?, headers: Map<String, String>): HttpResponse =
        host.http.execute(
            HttpRequest(url = url, method = if (body == null) "GET" else "POST", headers = headers, body = body, contentType = contentType),
        )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val API = "https://lfvp-api.dpgmedia.net"
        private const val AUTHORIZE_URL = "https://login.vtm.be/device/authorize"
        private const val TOKEN_URL = "https://login.vtm.be/token"
        private const val DEFAULT_VERIFICATION_URL = "https://login.vtm.be/androidtv"
        private const val CLIENT_ID = "vtm-go-androidtv"
        private const val SCOPE = "openid email profile address phone"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** Zoals de Android TV-app (API 28); oudere versies krijgen HTTP 426 "Upgrade required". */
        val API_HEADERS = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "VTM_GO/28.260923 (be.vmma.vtm.zenderapp; build:37208; Android TV 28) okhttp/4.12.0",
            "x-app-version" to "28",
            "lfvp-device-segment" to "TV>Android",
        )
        private val LOGIN_HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "okhttp/4.12.0")

        private const val AT = "access_token"
        private const val RT = "refresh_token"
        private const val EXP = "access_token.exp"
        private const val PROFILE = "profile"
        private const val PRODUCT = "product"
        private const val DEVICE_CODE = "device_code"

        /** `exp` uit een JWT (zonder de handtekening te controleren: enkel om op tijd te vernieuwen). */
        internal fun jwtExpiry(jwt: String): Long? = runCatching {
            val payload = jwt.split('.')[1]
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))))
            json.getLong("exp") * 1000
        }.getOrNull()
    }
}
