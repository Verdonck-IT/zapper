package be.verdonckit.zapper.plugins.play

import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.ProviderException
import org.json.JSONArray
import org.json.JSONObject

/** REST-API van Play (api.play.tv), met of zonder Bearer-token. */
internal class PlayApi(private val host: HostServices, private val auth: PlayAuth) {

    suspend fun getObject(path: String, authenticated: Boolean = true): JSONObject =
        parse(path, request(path, "GET", null, authenticated)) { JSONObject(it) }

    suspend fun getArray(path: String, authenticated: Boolean = true): JSONArray =
        parse(path, request(path, "GET", null, authenticated)) { JSONArray(it) }

    suspend fun put(path: String, json: JSONObject) {
        val resp = request(path, "PUT", json, authenticated = true)
        check(path, resp)
    }

    suspend fun delete(path: String) {
        val resp = request(path, "DELETE", null, authenticated = true)
        check(path, resp)
    }

    private suspend fun request(path: String, method: String, json: JSONObject?, authenticated: Boolean): HttpResponse {
        repeat(2) { attempt ->
            val headers = buildMap {
                put("Accept", "application/json")
                if (authenticated) put("Authorization", "Bearer ${auth.idToken()}")
            }
            val resp = try {
                host.http.execute(
                    HttpRequest(
                        url = if (path.startsWith("http")) path else API + path,
                        method = method,
                        headers = headers,
                        body = json?.toString()?.toByteArray(Charsets.UTF_8),
                        contentType = json?.let { "application/json" },
                    ),
                )
            } catch (e: Exception) {
                throw ProviderException(ErrorKind.NETWORK, "Play $path: ${e.message}", e)
            }
            if (resp.code == 401 && authenticated && attempt == 0) {
                auth.invalidate()
                return@repeat
            }
            return resp
        }
        throw ProviderException(ErrorKind.NOT_LOGGED_IN, "Play $path: 401 na token-vernieuwing")
    }

    private fun <T> parse(path: String, resp: HttpResponse, f: (String) -> T): T {
        check(path, resp)
        return runCatching { f(resp.text()) }.getOrElse {
            throw ProviderException(ErrorKind.API_CHANGED, "Play $path: geen geldige JSON (HTTP ${resp.code})")
        }
    }

    private fun check(path: String, resp: HttpResponse) {
        if (resp.code in 200..299) return
        val msg = runCatching { JSONObject(resp.text()).let { it.str("message") ?: it.str("errormsg") } }.getOrNull()
        val detail = "Play $path: HTTP ${resp.code}${msg?.let { " – $it" } ?: ""}"
        throw ProviderException(
            when (resp.code) {
                401 -> ErrorKind.NOT_LOGGED_IN
                451 -> ErrorKind.GEO_BLOCKED
                403, 404, 410 -> ErrorKind.NOT_AVAILABLE
                in 500..599 -> ErrorKind.NETWORK
                else -> ErrorKind.API_CHANGED
            },
            detail,
        )
    }

    companion object {
        const val API = "https://api.play.tv"
    }
}
