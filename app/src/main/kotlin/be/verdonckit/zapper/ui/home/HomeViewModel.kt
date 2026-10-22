package be.verdonckit.zapper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.ProgressEntity
import be.verdonckit.zapper.data.ProgressSync
import be.verdonckit.zapper.data.RemoteContinueEntity
import be.verdonckit.zapper.data.WatchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Wat er afgespeeld wordt als je een kaart uit "Verder kijken" kiest. */
data class PlayTarget(
    val providerId: String,
    val playableId: String,
    val title: String,
    val itemKey: String?,
    val imageUrl: String?,
)

data class Tile(
    val id: String,
    val title: String,
    /** Beelden in volgorde van voorkeur; de kaart valt per niveau terug op het volgende. */
    val imageUrls: List<String?>,
    val backdropUrls: List<String?>,
    val logoUrl: String?,
    val description: String?,
    val providerId: String,
    val progress: Float? = null,
    val item: ItemEntity? = null,
    val play: PlayTarget? = null,
    /** Kan uit "Verder kijken" verwijderd worden (OK ingedrukt houden). */
    val removable: Boolean = false,
)

data class HomeRow(val id: String, val title: String, val tiles: List<Tile>)

data class HomeState(
    val rows: List<HomeRow> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val providerColors: Map<String, Long> = emptyMap(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val dao = graph.dao
    private val categories = graph.categoryMapper.categories

    private val categoryRows = dao.categoriesWithContent().flatMapLatest { present ->
        // Live tv heeft een eigen pagina (menu > Live tv), niet meer als rij op het startscherm.
        val ordered = categories.filter { it.id in present && it.id != LIVE_CATEGORY }
        if (ordered.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(ordered.map { cat -> dao.rowFor(cat.id).map { items -> HomeRow(cat.id, cat.name, items.map(::tile)) } }) { it.toList() }
        }
    }

    private val continueRow = combine(
        dao.remoteContinue(),
        dao.continueWatching(),
        dao.recentProgress(System.currentTimeMillis() - ProgressSync.LOCAL_WINS_MS),
        graph.plugins.plugins,
    ) { remote, localInProgress, localRecent, plugins ->
        // Ook uit de tabel zelf: zo is de rij al juist voordat de plugins geladen zijn (geen verspringende focus).
        val syncing = plugins.values.filter { it.provider.info.syncsProgress }.map { it.id }.toSet() + remote.map { it.providerId }
        HomeRow("continue", "Verder kijken", continueTiles(remote, localInProgress, localRecent, syncing))
    }

    /**
     * Vooraan wat je net in Zapper keek (en het platform misschien nog niet weet), meest
     * recent eerst; daarna de lijst van het platform in de eigen volgorde; daarna lokale
     * voortgang van platformen zonder eigen lijst. Uitgekeken afleveringen verdwijnen.
     */
    private suspend fun continueTiles(
        remote: List<RemoteContinueEntity>,
        localInProgress: List<ProgressEntity>,
        localRecent: List<ProgressEntity>,
        syncing: Set<String>,
    ): List<Tile> {
        val recentByKey = localRecent.associateBy { it.playableKey }
        val now = System.currentTimeMillis()
        // Enkel echt kijken telt (≥ 30 s of uitgekeken), niet per ongeluk even starten.
        fun fresh(p: ProgressEntity?) = p != null && now - p.updatedAt < ProgressSync.LOCAL_WINS_MS &&
            (p.positionMs >= 30_000 || WatchState.isWatched(p.positionMs, p.durationMs))
        // Sorteersleutel: (laag, tijd) — laag 0 = net hier bekeken, 1 = platform, 2 = oudere lokale voortgang.
        val candidates = mutableListOf<Pair<Pair<Int, Long>, Tile>>()
        val seen = HashSet<String>()

        for (r in remote) {
            val local = recentByKey[r.playableKey]?.takeIf(::fresh)
            val pos = local?.positionMs ?: r.positionMs
            val dur = local?.durationMs?.takeIf { it > 0 } ?: r.durationMs
            if (WatchState.isWatched(pos, dur)) continue
            seen += r.playableKey
            val sortKey = if (local != null) 0 to local.updatedAt else 1 to -r.sortOrder.toLong()
            val item = r.itemKey?.let { dao.item(it) }
            candidates += sortKey to Tile(
                id = "resume:${r.playableKey}",
                title = r.itemTitle,
                imageUrls = listOf(r.imageUrl, item?.backdropUrl, item?.posterUrl),
                backdropUrls = listOf(item?.backdropUrl, r.imageUrl, item?.posterUrl),
                logoUrl = item?.logoUrl,
                description = episodeLine(r.seasonNumber, r.episodeNumber, r.episodeTitle, pos, dur),
                providerId = r.providerId,
                progress = if (dur > 0 && pos > 0) pos.toFloat() / dur else null,
                item = item,
                play = PlayTarget(r.providerId, r.playableId, be.verdonckit.zapper.ui.detail.playTitle(r.itemTitle, r.episodeTitle), r.itemKey, r.imageUrl ?: item?.backdropUrl),
                removable = graph.progressSync.canRemove(r.providerId),
            )
        }

        for (p in localInProgress) {
            if (p.playableKey in seen) continue
            // Bij een platform met eigen lijst enkel wat net hier bekeken werd.
            if (p.providerId in syncing && p.playableKey !in recentByKey) continue
            val item = p.itemKey?.let { dao.item(it) }
            val episodeTitle = item?.let { p.title.removePrefix("${it.title} – ") }?.takeIf { it != item.title && it != p.title }
            candidates += (if (fresh(p)) 0 else 2) to p.updatedAt to Tile(
                id = "resume:${p.playableKey}",
                title = item?.title ?: p.title,
                imageUrls = listOf(p.imageUrl, item?.backdropUrl, item?.posterUrl),
                backdropUrls = listOf(item?.backdropUrl, p.imageUrl, item?.posterUrl),
                logoUrl = item?.logoUrl,
                description = listOfNotNull(episodeTitle, remaining(p.positionMs, p.durationMs)).joinToString(" · "),
                providerId = p.providerId,
                progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null,
                item = item,
                play = PlayTarget(p.providerId, p.playableId, p.title, p.itemKey, p.imageUrl),
                removable = graph.progressSync.canRemove(p.providerId),
            )
        }
        return candidates
            .sortedWith(compareBy<Pair<Pair<Int, Long>, Tile>> { it.first.first }.thenByDescending { it.first.second })
            .map { it.second }
            .take(30)
    }

    /** "Mijn lijst" van alle platformen samen, meest recent toegevoegd eerst. */
    private val myListRow = dao.myList().map { list ->
        HomeRow(MY_LIST_ROW, "Mijn lijst", list.map { tile(it.toItem()) })
    }

    val state: StateFlow<HomeState> = combine(continueRow, myListRow, categoryRows, graph.plugins.plugins) { cont, myList, rows, plugins ->
        HomeState(
            rows = (listOf(cont, myList) + rows).filter { it.tiles.isNotEmpty() },
            providerNames = plugins.mapValues { it.value.name },
            providerColors = plugins.mapValues { it.value.provider.info.brandColor },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun removeFromContinue(tile: Tile) {
        val play = tile.play ?: return
        viewModelScope.launch { _message.value = graph.progressSync.remove(play.providerId, play.playableId) }
    }

    fun clearMessage() { _message.value = null }

    /** Bij terugkeer naar het startscherm: "Verder kijken" opnieuw bij de platformen ophalen. */
    fun refreshContinue(force: Boolean = false) {
        viewModelScope.launch { graph.progressSync.refresh(force) }
        viewModelScope.launch { graph.myListSync.refresh(force) }
    }

    private fun tile(e: ItemEntity) = Tile(
        id = e.key,
        title = e.title,
        // Kaart (16:9): liggend beeld → staand beeld (gecropt); daarna in de kaart zelf
        // titel-art op merkkleur → gegenereerde titelkaart.
        imageUrls = listOf(e.backdropUrl, e.posterUrl),
        backdropUrls = listOf(e.backdropUrl, e.posterUrl),
        logoUrl = e.logoUrl,
        description = e.description,
        providerId = e.providerId,
        item = e,
    )

    companion object {
        const val LIVE_CATEGORY = "live"
        const val MY_LIST_ROW = "mylist"

        fun episodeCode(season: Int?, episode: Int?): String? = when {
            season != null && episode != null -> "S$season A$episode"
            episode != null -> "Aflevering $episode"
            else -> null
        }

        private fun episodeLine(season: Int?, episode: Int?, title: String, pos: Long, dur: Long): String =
            listOfNotNull(
                listOfNotNull(episodeCode(season, episode), title).joinToString(" – "),
                if (pos > 0) remaining(pos, dur) else "Volgende aflevering",
            ).joinToString(" · ")

        fun remaining(pos: Long, dur: Long): String? {
            if (dur <= 0) return null
            val min = ((dur - pos).coerceAtLeast(0) / 60_000).toInt()
            return if (min < 1) "minder dan 1 min over" else "nog $min min"
        }
    }
}
