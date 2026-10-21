package be.verdonckit.zapper.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.api.StreamingProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Widevine-licenties: standaard een gewone POST met de headers van de plugin;
 * als [StreamInfo.drm].handledByProvider true is, doet de plugin het zelf
 * (voor platformen die de challenge verpakken of ondertekenen).
 * Media3 roept dit aan op een achtergrondthread, dus blokkeren mag.
 */
@OptIn(UnstableApi::class)
class ProviderDrmCallback(
    private val provider: StreamingProvider,
    private val stream: StreamInfo,
    private val client: OkHttpClient,
    dataSourceFactory: DataSource.Factory,
) : MediaDrmCallback {

    private val provisioning = HttpMediaDrmCallback(null, dataSourceFactory)

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        provisioning.executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val drm = stream.drm ?: throw IOException("stream zonder DRM-info vraagt toch een licentie")
        if (drm.handledByProvider) {
            return runBlocking { provider.acquireLicense(stream, request.data) }
        }
        val url = drm.licenseUrl?.takeIf { it.isNotBlank() } ?: request.licenseServerUrl
        val builder = Request.Builder()
            .url(url)
            .post(request.data.toRequestBody("application/octet-stream".toMediaType()))
        drm.licenseHeaders.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("licentieserver gaf HTTP ${r.code}")
            return r.body!!.bytes()
        }
    }
}
