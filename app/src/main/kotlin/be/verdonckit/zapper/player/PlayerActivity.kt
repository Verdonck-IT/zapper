package be.verdonckit.zapper.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import be.verdonckit.zapper.api.ErrorKind
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.StreamFormat
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.core.OkHttpHostHttp
import be.verdonckit.zapper.data.ProgressEntity
import be.verdonckit.zapper.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private var progressJob: Job? = null

    private val providerId by lazy { intent.getStringExtra(EXTRA_PROVIDER)!! }
    private val playableId by lazy { intent.getStringExtra(EXTRA_PLAYABLE)!! }
    private val title by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
    private val itemKey by lazy { intent.getStringExtra(EXTRA_ITEM_KEY) }
    private val imageUrl by lazy { intent.getStringExtra(EXTRA_IMAGE) }
    private var isLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply { keepScreenOn = true }
        message = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(96, 0, 96, 0)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        message.text = "Laden: $title"
        start()
    }

    private fun start() = lifecycleScope.launch {
        val plugin = graph.plugins.get(providerId)
            ?: return@launch showError("Plugin $providerId is niet geladen.")
        val stream = try {
            plugin.provider.resolveStream(playableId)
        } catch (e: ProviderException) {
            return@launch showError(friendly(e))
        } catch (e: Exception) {
            return@launch showError("Kon de stream niet ophalen: ${e.message}")
        }
        isLive = stream.isLive

        val resumeAt = if (stream.isLive) 0L else graph.dao.progress(progressKey())
            ?.takeIf { it.positionMs > 30_000 && (it.durationMs == 0L || it.positionMs < it.durationMs * 0.95) }
            ?.positionMs ?: 0L

        play(plugin.provider, stream, resumeAt)
    }

    private fun play(provider: be.verdonckit.zapper.api.StreamingProvider, stream: StreamInfo, resumeAt: Long) {
        val okHttp = graph.okHttp
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(OkHttpHostHttp.USER_AGENT)
            .setDefaultRequestProperties(stream.requestHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        if (stream.drm != null) {
            val drmManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(stream.isLive)
                .build(ProviderDrmCallback(provider, stream, okHttp, dataSourceFactory))
            mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
        }

        val item = MediaItem.Builder()
            .setUri(stream.manifestUrl)
            .setMimeType(
                when (stream.format) {
                    StreamFormat.DASH -> MimeTypes.APPLICATION_MPD
                    StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
                    StreamFormat.PROGRESSIVE -> null
                },
            )
            .apply { if (stream.drm != null) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build()) }
            .setSubtitleConfigurations(
                stream.subtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url))
                        .setLanguage(it.language).setMimeType(it.mimeType).build()
                },
            )
            .build()

        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) message.text = ""
                if (state == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                val drmHint = if (error.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED) {
                    "\n(DRM-fout: controleer of dit toestel Widevine ondersteunt)"
                } else ""
                showError("Afspelen mislukt: ${error.errorCodeName}$drmHint")
            }
        })
        playerView.player = exo
        exo.setMediaItem(item, resumeAt)
        exo.prepare()
        exo.playWhenReady = true
        player = exo

        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private suspend fun saveProgress() {
        val p = player ?: return
        if (isLive || p.currentPosition <= 0) return
        graph.dao.saveProgress(
            ProgressEntity(
                playableKey = progressKey(),
                providerId = providerId,
                playableId = playableId,
                itemKey = itemKey,
                title = title,
                imageUrl = imageUrl,
                positionMs = p.currentPosition,
                durationMs = p.duration.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun progressKey() = "$providerId:$playableId"

    private fun showError(text: String) {
        message.text = "$text\n\nDruk op terug om te sluiten."
    }

    private fun friendly(e: ProviderException) = when (e.kind) {
        ErrorKind.NOT_LOGGED_IN -> "Je bent niet (meer) ingelogd bij deze zender. Log opnieuw in via Instellingen."
        ErrorKind.GEO_BLOCKED -> "Deze inhoud is enkel beschikbaar in België."
        ErrorKind.NOT_AVAILABLE -> "Deze aflevering is niet (meer) beschikbaar."
        ErrorKind.AGE_RESTRICTED -> "Je account heeft geen toegang tot deze inhoud (leeftijdsgrens)."
        ErrorKind.API_CHANGED -> "Het platform is gewijzigd. De plugin wordt automatisch bijgewerkt zodra er een fix is.\n(${e.message})"
        ErrorKind.NETWORK -> "Netwerkfout: ${e.message}"
        ErrorKind.UNKNOWN -> "Fout: ${e.message}"
    }

    override fun onStop() {
        super.onStop()
        progressJob?.cancel()
        val p = player
        if (p != null) {
            // lifecycleScope stopt niet bij onStop, dus dit wordt nog weggeschreven.
            lifecycleScope.launch { saveProgress(); p.release() }
            player = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && message.text.isNullOrEmpty()) start()
    }

    companion object {
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_PLAYABLE = "playable"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ITEM_KEY = "itemKey"
        private const val EXTRA_IMAGE = "image"

        fun intent(context: Context, providerId: String, playableId: String, title: String, itemKey: String?, imageUrl: String?) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PROVIDER, providerId)
                .putExtra(EXTRA_PLAYABLE, playableId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ITEM_KEY, itemKey)
                .putExtra(EXTRA_IMAGE, imageUrl)
    }
}
