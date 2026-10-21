package be.verdonckit.zapper.player

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import androidx.media3.common.Tracks
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
import be.verdonckit.zapper.data.ProgressSync
import be.verdonckit.zapper.data.WatchState
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.plugin.LoadedPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.ui.R as Media3R

/**
 * Afspelen met afstandsbediening:
 * - ◀ / ▶ spoelen 10 s; ingedrukt houden versnelt naar 30 s en 60 s per stap.
 * - OK pauzeert / speelt verder.
 * - ▼ toont de balk met de ondertitelknop (CC); ▲ gaat terug.
 * - De CC-toets op de afstandsbediening zet ondertitels aan/uit. De keuze wordt onthouden.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private lateinit var seekLabel: TextView
    private var progressJob: Job? = null
    private var plugin: LoadedPlugin? = null
    private var stream: StreamInfo? = null

    private val providerId by lazy { intent.getStringExtra(EXTRA_PROVIDER)!! }
    private val playableId by lazy { intent.getStringExtra(EXTRA_PLAYABLE)!! }
    private val title by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
    private val itemKey by lazy { intent.getStringExtra(EXTRA_ITEM_KEY) }
    private val imageUrl by lazy { intent.getStringExtra(EXTRA_IMAGE) }
    private val fromStart by lazy { intent.getBooleanExtra(EXTRA_FROM_START, false) }
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private var isLive = false

    // Spoelen
    private var seekKeyDownAt = 0L
    private var lastSeekAt = 0L
    private var seekStartPosition = 0L
    private val hideSeekLabel = Runnable { seekLabel.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            // Geen +/- knoppen: spoelen gebeurt met ◀ / ▶ op de afstandsbediening.
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setShowPreviousButton(false)
            setShowNextButton(false)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 4000
        }
        // De tijdbalk zelf mag geen focus nemen, anders vangt die ◀ / ▶ op met rare stappen.
        playerView.findViewById<View>(Media3R.id.exo_progress)?.isFocusable = false

        message = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(96, 0, 96, 0)
        }
        seekLabel = TextView(this).apply {
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(36, 16, 36, 16)
            background = GradientDrawable().apply { cornerRadius = 16f; setColor(0xB3000000.toInt()) }
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                seekLabel,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
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
        this@PlayerActivity.plugin = plugin
        this@PlayerActivity.stream = stream
        isLive = stream.isLive
        play(plugin, stream, resumePosition(plugin, stream))
    }

    /**
     * Platform met eigen voortgang: dat is de bron, behalve als je hier net (< 6 u) nog keek.
     * Uitgekeken = opnieuw van het begin.
     */
    private suspend fun resumePosition(plugin: LoadedPlugin, stream: StreamInfo): Long {
        if (stream.isLive || fromStart) return 0
        val local = graph.dao.progress(progressKey())
        val remote = stream.resumePositionSeconds?.let { it * 1000L }
        val syncs = plugin.provider.info.syncsProgress
        val localFresh = local != null && System.currentTimeMillis() - local.updatedAt < ProgressSync.LOCAL_WINS_MS
        val pos = when {
            local != null && (localFresh || !syncs) ->
                if (WatchState.isWatched(local.positionMs, local.durationMs)) 0 else local.positionMs
            remote != null -> remote
            else -> 0
        }
        return if (pos > 30_000) pos else 0
    }

    private fun play(plugin: LoadedPlugin, stream: StreamInfo, resumeAt: Long) {
        val okHttp = graph.okHttp
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(OkHttpHostHttp.USER_AGENT)
            .setDefaultRequestProperties(stream.requestHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        if (stream.drm != null) {
            val drmManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(stream.isLive)
                .build(ProviderDrmCallback(plugin.provider, stream, okHttp, dataSourceFactory))
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
        applySubtitlePreference(exo, prefs.getBoolean(PREF_SUBS, false))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) message.text = ""
                if (state == Player.STATE_ENDED) finish()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Keuze via de CC-knop onthouden voor de volgende aflevering.
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (text.isEmpty()) return
                val selected = text.firstOrNull { it.isSelected }
                val editor = prefs.edit().putBoolean(PREF_SUBS, selected != null)
                selected?.mediaTrackGroup?.getFormat(0)?.language?.let { editor.putString(PREF_SUBS_LANG, it) }
                editor.apply()
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
            var ticks = 0
            while (isActive) {
                delay(10_000)
                saveProgress()
                // Elke 30 s ook naar het platform, zodat de eigen app/website mee is.
                if (++ticks % 3 == 0) reportProgress()
            }
        }
    }

    // ---------- Ondertitels ----------

    private fun applySubtitlePreference(p: Player, enabled: Boolean) {
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        p.trackSelectionParameters = if (enabled) {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(prefs.getString(PREF_SUBS_LANG, null) ?: "nl")
                .setIgnoredTextSelectionFlags(0)
                .build()
        } else {
            // Zoals "Uit" in het CC-menu: enkel geforceerde ondertitels (vertaling van anderstalige stukken).
            builder
                .setPreferredTextLanguage(null)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                .build()
        }
    }

    private fun toggleSubtitles() {
        val p = player ?: return
        val hasText = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (!hasText) return flash("Geen ondertitels beschikbaar")
        val on = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        applySubtitlePreference(p, !on)
        flash(if (on) "Ondertitels uit" else "Ondertitels aan")
    }

    // ---------- Afstandsbediening ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // In de onderste balk (CC, instellingen) blijven ◀ / ▶ gewoon navigeren.
                if (focusInBottomBar()) return super.dispatchKeyEvent(event)
                if (down) seekStep(p, event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekStep(p, event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (focusInBottomBar()) return super.dispatchKeyEvent(event)
                if (down && event.repeatCount == 0) {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusInBottomBar()) return super.dispatchKeyEvent(event)
                if (down) focusSubtitleButton()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusInBottomBar()) {
                    if (down) {
                        playerView.findViewById<View>(Media3R.id.exo_play_pause)?.requestFocus()
                        playerView.showController()
                    }
                    return true
                }
                if (down) playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                if (down && event.repeatCount == 0) toggleSubtitles()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Eén druk = 10 s. Ingedrukt houden: na 1,5 s stappen van 30 s, na 4 s van 60 s,
     * maximaal ~7 stappen per seconde zodat je kan volgen waar je zit.
     */
    private fun seekStep(p: Player, forward: Boolean, event: KeyEvent) {
        if (!p.isCurrentMediaItemSeekable) return
        val now = event.eventTime
        if (event.repeatCount == 0) {
            seekKeyDownAt = now
            seekStartPosition = p.currentPosition
        } else if (now - lastSeekAt < 140) {
            return
        }
        lastSeekAt = now
        val held = now - seekKeyDownAt
        val step = when {
            held < 1_500 -> 10_000L
            held < 4_000 -> 30_000L
            else -> 60_000L
        }
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val max = duration?.let { (it - 2_000).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val target = (p.currentPosition + if (forward) step else -step).coerceIn(0, max)
        p.seekTo(target)
        playerView.showController()

        val delta = target - seekStartPosition
        val sign = if (delta >= 0) "+" else "−"
        seekLabel.text = "${if (forward) "»" else "«"}  $sign${format(kotlin.math.abs(delta))}   ${format(target)}" +
            (duration?.let { " / ${format(it)}" } ?: "")
        seekLabel.visibility = View.VISIBLE
        seekLabel.removeCallbacks(hideSeekLabel)
        seekLabel.postDelayed(hideSeekLabel, 1_200)
    }

    private fun focusSubtitleButton() {
        playerView.showController()
        val subs = playerView.findViewById<View>(Media3R.id.exo_subtitle)
        val target = subs?.takeIf { it.visibility == View.VISIBLE && it.isEnabled }
            ?: playerView.findViewById<View>(Media3R.id.exo_settings)
        target?.requestFocus()
    }

    private fun focusInBottomBar(): Boolean {
        if (!playerView.isControllerFullyVisible) return false
        var v: View? = currentFocus
        while (v != null) {
            if (v.id == Media3R.id.exo_bottom_bar) return true
            v = v.parent as? View
        }
        return false
    }

    private fun flash(text: String) {
        seekLabel.text = text
        seekLabel.visibility = View.VISIBLE
        seekLabel.removeCallbacks(hideSeekLabel)
        seekLabel.postDelayed(hideSeekLabel, 1_500)
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---------- Voortgang ----------

    private suspend fun saveProgress(position: Long? = null, duration: Long? = null) {
        val p = player
        val pos = position ?: p?.currentPosition ?: return
        val dur = duration ?: p?.duration?.coerceAtLeast(0) ?: 0
        if (isLive || pos <= 0) return
        graph.dao.saveProgress(
            ProgressEntity(
                playableKey = progressKey(),
                providerId = providerId,
                playableId = playableId,
                itemKey = itemKey,
                title = title,
                imageUrl = imageUrl,
                positionMs = pos,
                durationMs = dur,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun reportProgress(position: Long? = null, duration: Long? = null) {
        val plugin = plugin ?: return
        val stream = stream ?: return
        val p = player
        val pos = position ?: p?.currentPosition ?: return
        val dur = duration ?: p?.duration?.takeIf { it != C.TIME_UNSET } ?: return
        graph.progressSync.report(plugin, stream, playableId, pos, dur)
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
        val p = player ?: return
        val ended = p.playbackState == Player.STATE_ENDED
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val position = if (ended && duration > 0) duration else p.currentPosition
        p.release()
        player = null
        // App-scope: de activity mag intussen weg zijn, dit moet nog weggeschreven en gemeld worden.
        graph.scope.launch {
            saveProgress(position, duration)
            reportProgress(position, duration)
            graph.progressSync.refresh(force = true)
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
        private const val EXTRA_FROM_START = "fromStart"
        private const val PREF_SUBS = "subtitles.enabled"
        private const val PREF_SUBS_LANG = "subtitles.language"

        fun intent(
            context: Context,
            providerId: String,
            playableId: String,
            title: String,
            itemKey: String?,
            imageUrl: String?,
            fromStart: Boolean = false,
        ) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_FROM_START, fromStart)
                .putExtra(EXTRA_PROVIDER, providerId)
                .putExtra(EXTRA_PLAYABLE, playableId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ITEM_KEY, itemKey)
                .putExtra(EXTRA_IMAGE, imageUrl)
    }
}
