package be.verdonckit.zapper.api

/** Wat de host aan een plugin aanbiedt. */
interface HostServices {
    val http: HostHttp

    /** Versleutelde key-value opslag, afgeschermd per provider. Voor tokens en sessies. */
    val storage: KeyValueStore

    /** De opgeslagen login van de gebruiker voor deze provider, om stil opnieuw in te loggen. */
    fun credentials(): Credentials?

    fun log(level: LogLevel, message: String, error: Throwable? = null)

    fun nowMillis(): Long = System.currentTimeMillis()
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/**
 * HTTP met een cookie-jar per provider.
 *
 * - Redirects worden door de host zelf gevolgd zodat cookies van tussenliggende
 *   responses in de jar terechtkomen.
 * - Een expliciete `Cookie`-header in de request wordt samengevoegd met de jar
 *   (expliciete waarden winnen).
 */
interface HostHttp {
    suspend fun execute(request: HttpRequest): HttpResponse

    /** Cookies die de jar voor deze URL zou meesturen. */
    fun cookies(url: String): Map<String, String>

    fun clearCookies()
}

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val followRedirects: Boolean = true,
)

class HttpResponse(
    val code: Int,
    /** Header-namen in lowercase. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val finalUrl: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
    val isRedirect: Boolean get() = code in 300..399

    fun text(): String = body.toString(Charsets.UTF_8)

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    fun setCookieHeaders(): List<String> = headers["set-cookie"].orEmpty()
}

// ---- Gemaksfuncties voor plugins ----

suspend fun HostHttp.get(
    url: String,
    headers: Map<String, String> = emptyMap(),
    followRedirects: Boolean = true,
): HttpResponse = execute(HttpRequest(url, "GET", headers, followRedirects = followRedirects))

suspend fun HostHttp.postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
    followRedirects: Boolean = true,
): HttpResponse = execute(
    HttpRequest(
        url = url,
        method = "POST",
        headers = headers,
        body = json.toByteArray(Charsets.UTF_8),
        contentType = "application/json",
        followRedirects = followRedirects,
    ),
)
