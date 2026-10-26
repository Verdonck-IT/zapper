package be.verdonckit.zapper.plugins.vtmgo

import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.ProviderException
import org.json.JSONObject

/** REST-API van VTM GO (lfvp-api.dpgmedia.net) met Bearer-token en profiel, en de player-config. */
internal class VtmGoApi(private val host: HostServices, private val auth: VtmGoAuth) {

    suspend fun get(path: String): JSONObject = parse(path, request(path, "GET"))

    suspend fun put(path: String) = check(path, request(path, "PUT", ByteArray(0)))

    suspend fun delete(path: String) = check(path, request(path, "DELETE"))

    /** Streamconfiguratie bij de videoplayer-service, met het playerToken uit /play of /live. */
    suspend fun playConfig(id: String, playerToken: String): JSONObject {
        val body = JSONObject().put("deviceType", "android-tv").put("zone", "vtmgo").toString().toByteArray()
        val resp = execute(
            HttpRequest(
                url = "$PLAYER_API/play-config/$id?startPosition=0.0&autoPlay=true",
                method = "POST",
                headers = mapOf(
                    "Accept" to "application/json",
                    "x-api-key" to PLAYER_API_KEY,
                    "Popcorn-SDK-Version" to "8",
                    "Authorization" to "Bearer $playerToken",
                ),
                body = body,
                contentType = "application/json",
            ),
            "play-config",
        )
        val json = runCatching { JSONObject(resp.text()) }.getOrNull()
            ?: throw ProviderException(ErrorKind.API_CHANGED, "VTM GO play-config: geen JSON (HTTP ${resp.code})")
        if (json.obj("video") == null) {
            val type = json.str("type") ?: json.str("code") ?: "HTTP ${resp.code}"
            throw ProviderException(
                when {
                    type.contains("geo", ignoreCase = true) -> ErrorKind.GEO_BLOCKED
                    resp.code >= 500 -> ErrorKind.NETWORK
                    else -> ErrorKind.NOT_AVAILABLE
                },
                "VTM GO: afspelen niet mogelijk ($type)",
            )
        }
        return json
    }

    /** Ruwe POST zonder VTM GO-headers, voor de licentieserver. */
    suspend fun postRaw(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse =
        execute(HttpRequest(url, "POST", headers, body, headers["Content-Type"]), "licentie")

    private suspend fun request(path: String, method: String, body: ByteArray? = null): HttpResponse {
        repeat(2) { attempt ->
            val headers = VtmGoAuth.API_HEADERS + mapOf(
                "Authorization" to "Bearer ${auth.accessToken()}",
                "x-dpp-profile" to auth.profile(),
            )
            val resp = execute(HttpRequest("${VtmGoAuth.API}/$MODE$path", method, headers, body, body?.let { "application/json" }), path)
            if (resp.code == 401 && attempt == 0) {
                auth.invalidate()
                return@repeat
            }
            return resp
        }
        throw ProviderException(ErrorKind.NOT_LOGGED_IN, "VTM GO $path: 401 na token-vernieuwing")
    }

    private suspend fun execute(request: HttpRequest, what: String): HttpResponse = try {
        host.http.execute(request)
    } catch (e: Exception) {
        throw ProviderException(ErrorKind.NETWORK, "VTM GO $what: ${e.message}", e)
    }

    private fun parse(path: String, resp: HttpResponse): JSONObject {
        check(path, resp)
        // Mijn lijst is leeg = lege body.
        if (resp.body.isEmpty()) return JSONObject()
        return runCatching { JSONObject(resp.text()) }.getOrElse {
            throw ProviderException(ErrorKind.API_CHANGED, "VTM GO $path: geen geldige JSON (HTTP ${resp.code})")
        }
    }

    private fun check(path: String, resp: HttpResponse) {
        if (resp.code in 200..299) return
        val type = runCatching { JSONObject(resp.text()).str("type") }.getOrNull()
        val detail = "VTM GO $path: HTTP ${resp.code}${type?.let { " – $it" } ?: ""}"
        throw ProviderException(
            when {
                resp.code == 401 -> ErrorKind.NOT_LOGGED_IN
                type == "videoPlaybackGeoblocked" -> ErrorKind.GEO_BLOCKED
                resp.code == 403 || resp.code == 404 || resp.code == 410 -> ErrorKind.NOT_AVAILABLE
                resp.code == 429 || resp.code >= 500 -> ErrorKind.NETWORK
                // 426 "Upgrade required": de app-versie in de headers is te oud.
                else -> ErrorKind.API_CHANGED
            },
            detail,
        )
    }

    companion object {
        private const val MODE = "VTM_GO"
        private const val PLAYER_API = "https://videoplayer-service.dpgmedia.net"
        private const val PLAYER_API_KEY = "r9EOnHOp1pPL5L4FuGzBPSIHwrQnPu5TBfW16y75"
    }
}
