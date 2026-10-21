package be.verdonckit.zapper.testkit

import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.HostHttp
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.KeyValueStore
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.support.RedirectingHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JRequest
import java.net.http.HttpResponse as JResponse
import java.time.Duration

/** Host voor plugins op een gewone JVM: voor contracttests en lokaal debuggen. */
class JvmHostServices(
    private val creds: Credentials?,
    userAgent: String = DEFAULT_UA,
) : HostServices {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override val http: HostHttp = object : RedirectingHttp(defaultHeaders = mapOf("User-Agent" to userAgent)) {
        override suspend fun sendOnce(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: ByteArray?,
            contentType: String?,
        ): HttpResponse = withContext(Dispatchers.IO) {
            val builder = JRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
            headers.forEach { (k, v) -> if (!RESTRICTED.contains(k.lowercase())) builder.header(k, v) }
            contentType?.let { builder.header("Content-Type", it) }
            val publisher = body?.let { JRequest.BodyPublishers.ofByteArray(it) } ?: JRequest.BodyPublishers.noBody()
            builder.method(method, publisher)
            val r = client.send(builder.build(), JResponse.BodyHandlers.ofByteArray())
            HttpResponse(
                code = r.statusCode(),
                headers = r.headers().map().mapKeys { it.key.lowercase() },
                body = r.body(),
                finalUrl = url,
            )
        }
    }

    override val storage: KeyValueStore = object : KeyValueStore {
        private val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
    }

    override fun credentials(): Credentials? = creds

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        // Nooit tokens loggen: plugins horen enkel diagnose-info door te geven.
        System.err.println("[$level] $message${error?.let { " — ${it.message}" } ?: ""}")
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
        private val RESTRICTED = setOf("host", "connection", "content-length", "expect", "upgrade")
    }
}
