package be.verdonckit.zapper.api.support

import be.verdonckit.zapper.api.HostHttp
import be.verdonckit.zapper.api.HttpRequest
import be.verdonckit.zapper.api.HttpResponse
import java.net.URI

/**
 * Gedeelde HostHttp-logica: cookie-jar en zelf redirects volgen.
 * Een host hoeft enkel [sendOnce] te implementeren (één request, géén redirects volgen).
 */
abstract class RedirectingHttp(
    private val jar: SimpleCookieJar = SimpleCookieJar(),
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val maxRedirects: Int = 10,
) : HostHttp {

    /** Voert exact één request uit zonder redirects te volgen. Headers bevatten al de Cookie-header. */
    protected abstract suspend fun sendOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): HttpResponse

    override suspend fun execute(request: HttpRequest): HttpResponse {
        var url = request.url
        var method = request.method
        var body = request.body
        var contentType = request.contentType
        val explicitCookie = request.headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        val baseHeaders = defaultHeaders + request.headers.filterKeys { !it.equals("Cookie", true) }

        repeat(maxRedirects + 1) {
            val headers = LinkedHashMap(baseHeaders)
            jar.cookieHeader(url, explicitCookie)?.let { headers["Cookie"] = it }
            val response = sendOnce(url, method, headers, body, contentType)
            jar.saveFromResponse(url, response.setCookieHeaders())

            val location = response.header("location")
            if (!request.followRedirects || !response.isRedirect || location == null) return response

            url = URI(url).resolve(location).toString()
            if (response.code == 303 || ((response.code == 301 || response.code == 302) && method == "POST")) {
                method = "GET"
                body = null
                contentType = null
            }
        }
        throw IllegalStateException("Te veel redirects voor ${request.url}")
    }

    override fun cookies(url: String): Map<String, String> = jar.cookiesFor(url)

    override fun clearCookies() = jar.clear()
}
