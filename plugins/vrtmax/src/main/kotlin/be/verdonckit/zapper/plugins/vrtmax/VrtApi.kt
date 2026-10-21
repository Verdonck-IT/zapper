package be.verdonckit.zapper.plugins.vrtmax

import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.get
import be.verdonckit.zapper.api.postJson
import org.json.JSONObject
import java.net.URLEncoder

internal class VrtApi(private val host: HostServices, private val auth: VrtAuth) {

    suspend fun graphql(operation: String, query: String, variablesJson: String): JSONObject {
        repeat(2) { attempt ->
            val body = "{\"operationName\":${jsonString(operation)},\"query\":${jsonString(query.trimIndent())},\"variables\":$variablesJson}"
            val resp = host.http.postJson(
                GRAPHQL_URL,
                body,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${auth.accessToken()}",
                    "x-vrt-client-name" to "WEB",
                    "x-vrt-client-version" to CLIENT_VERSION,
                ),
            )
            if (resp.code == 401 && attempt == 0) {
                auth.invalidateAccessToken()
                return@repeat
            }
            if (resp.code >= 500) throw ProviderException(ErrorKind.NETWORK, "GraphQL $operation: HTTP ${resp.code}")
            val json = runCatching { JSONObject(resp.text()) }.getOrElse {
                throw ProviderException(ErrorKind.API_CHANGED, "GraphQL $operation: geen JSON (HTTP ${resp.code})")
            }
            val errors = json.arr("errors").objects()
            val data = json.obj("data")
            if (errors.isNotEmpty() && (data == null || data.length() == 0 || data.isNull(data.keys().next()))) {
                val msg = errors.joinToString(" | ") { it.str("message") ?: it.toString() }
                throw ProviderException(ErrorKind.API_CHANGED, "GraphQL $operation: $msg")
            }
            return data ?: throw ProviderException(ErrorKind.API_CHANGED, "GraphQL $operation: geen data (HTTP ${resp.code})")
        }
        throw ProviderException(ErrorKind.NOT_LOGGED_IN, "GraphQL $operation: 401 na token-vernieuwing")
    }

    /** Media-aggregator: streamId → manifest + DRM-token. */
    suspend fun mediaItem(streamId: String, live: Boolean): JSONObject {
        val token = auth.playerToken(live)
        val url = "$MEDIA_API/media-items/${URLEncoder.encode(streamId, "UTF-8")}" +
            "?vrtPlayerToken=${URLEncoder.encode(token, "UTF-8")}&client=$MEDIA_CLIENT"
        val resp = host.http.get(url)
        return runCatching { JSONObject(resp.text()) }.getOrElse {
            throw ProviderException(ErrorKind.API_CHANGED, "media-items: geen JSON (HTTP ${resp.code})")
        }
    }

    companion object {
        const val GRAPHQL_URL = "https://www.vrt.be/vrtnu-api/graphql/v1"
        const val CLIENT_VERSION = "1.5.15"
        const val MEDIA_API = "https://media-services-public.vrt.be/media-aggregator/v2"
        const val MEDIA_CLIENT = "vrtnu-web@PROD"
    }
}
