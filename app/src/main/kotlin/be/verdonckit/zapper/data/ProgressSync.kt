package be.verdonckit.zapper.data

import android.util.Log
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.StreamInfo
import be.verdonckit.zapper.api.WatchProgress
import be.verdonckit.zapper.plugin.LoadedPlugin
import be.verdonckit.zapper.plugin.PluginManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kijkvoortgang. Voor platformen met [be.verdonckit.zapper.api.ProviderInfo.syncsProgress]
 * is het platform de bron van waarheid ("Verder kijken", bekeken afleveringen); de lokale
 * tabel `progress` vult enkel aan wat net in Zapper bekeken werd en nog niet terug bij het
 * platform zichtbaar is.
 */
class ProgressSync(private val dao: CatalogDao, private val plugins: PluginManager) {

    private val mutex = Mutex()
    private var lastRefresh = 0L

    /** Ververst de "Verder kijken"-lijsten van alle platformen die dat ondersteunen. */
    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < MIN_REFRESH_INTERVAL_MS) return@withLock
        lastRefresh = now
        for (plugin in plugins.plugins.value.values) {
            if (!plugin.provider.info.syncsProgress) continue
            runCatching { refreshOne(plugin, now) }
                .onFailure { Log.w(TAG, "${plugin.id}: verder kijken ophalen mislukt: ${it.message}") }
        }
    }

    private suspend fun refreshOne(plugin: LoadedPlugin, now: Long) {
        val p = plugin.provider
        if (!p.isLoggedIn()) return
        val entries = p.continueWatching().mapIndexed { index, e ->
            val ep = e.episode
            RemoteContinueEntity(
                playableKey = "${plugin.id}:${ep.playableId}",
                providerId = plugin.id,
                playableId = ep.playableId,
                itemKey = e.itemId?.let { "${plugin.id}:$it" },
                itemTitle = e.itemTitle,
                episodeTitle = ep.title,
                imageUrl = ep.imageUrl,
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                positionMs = (ep.progress?.positionSeconds ?: 0) * 1000L,
                durationMs = ((ep.progress?.durationSeconds ?: ep.durationSeconds) ?: 0) * 1000L,
                sortOrder = index,
                fetchedAt = now,
            )
        }.distinctBy { it.playableKey }
        dao.replaceRemoteContinue(plugin.id, entries)
    }

    /** Voortgang terugmelden aan het platform. Fouten breken het afspelen nooit. */
    suspend fun report(plugin: LoadedPlugin, stream: StreamInfo, playableId: String, positionMs: Long, durationMs: Long) {
        if (!plugin.provider.info.syncsProgress || stream.isLive || durationMs <= 0) return
        runCatching {
            plugin.provider.reportProgress(stream, playableId, (positionMs / 1000).toInt(), (durationMs / 1000).toInt())
        }.onFailure { Log.w(TAG, "${plugin.id}: voortgang terugmelden mislukt: ${it.message}") }
    }

    companion object {
        private const val TAG = "ProgressSync"
        private const val MIN_REFRESH_INTERVAL_MS = 60_000L

        /** Lokale voortgang die recenter is dan dit, gaat voor op wat het platform zegt. */
        const val LOCAL_WINS_MS = 6 * 3600 * 1000L
    }
}

/** Hoe ver een aflevering bekeken is, na het samenvoegen van platform en lokaal. */
data class WatchState(val positionMs: Long, val durationMs: Long, val watched: Boolean) {
    val fraction: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    val started: Boolean get() = positionMs > 0 || watched
    val inProgress: Boolean get() = started && !watched
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        /** Laatste 30 s of 95 %: telt als uitgekeken (zelfde marge als de platformen). */
        fun isWatched(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs >= durationMs - 30_000 || positionMs >= durationMs * 0.95)

        fun of(remote: WatchProgress?, local: ProgressEntity?, now: Long = System.currentTimeMillis()): WatchState? {
            val localState = local?.let { WatchState(it.positionMs, it.durationMs, isWatched(it.positionMs, it.durationMs)) }
            val remoteState = remote?.let {
                val pos = it.positionSeconds * 1000L
                val dur = it.durationSeconds * 1000L
                WatchState(if (it.completed) maxOf(pos, dur) else pos, dur, it.completed || isWatched(pos, dur))
            }
            return when {
                localState != null && (remoteState == null || now - local.updatedAt < ProgressSync.LOCAL_WINS_MS) -> localState
                else -> remoteState
            }
        }

        fun of(episode: Episode, local: ProgressEntity?) = of(episode.progress, local)
    }
}
