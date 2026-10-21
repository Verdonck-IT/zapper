package be.verdonckit.zapper.core

import android.util.Log
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.HostHttp
import be.verdonckit.zapper.api.HostServices
import be.verdonckit.zapper.api.HttpResponse
import be.verdonckit.zapper.api.KeyValueStore
import be.verdonckit.zapper.api.LogLevel
import be.verdonckit.zapper.api.support.RedirectingHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HostHttp op OkHttp. Elke provider krijgt een eigen instantie (en dus een eigen cookie-jar). */
class OkHttpHostHttp(client: OkHttpClient) : RedirectingHttp(defaultHeaders = mapOf("User-Agent" to USER_AGENT)) {

    private val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun sendOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val requestBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder().url(url).headers(headers.toHeaders()).method(method, requestBody).build()
        noRedirects.newCall(request).execute().use { r ->
            HttpResponse(
                code = r.code,
                headers = r.headers.toMultimap(), // OkHttp geeft lowercase namen
                body = r.body?.bytes() ?: ByteArray(0),
                finalUrl = r.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0"
    }
}

class AndroidHostServices(
    private val providerId: String,
    override val http: HostHttp,
    override val storage: KeyValueStore,
    private val credentialStore: CredentialStore,
) : HostServices {

    override fun credentials(): Credentials? = credentialStore.get(providerId)

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        val tag = "plugin.$providerId"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }
}
