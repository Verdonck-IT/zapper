package be.verdonckit.zapper.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.Season
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.ProgressEntity
import be.verdonckit.zapper.data.ProgressSync
import be.verdonckit.zapper.data.WatchState
import be.verdonckit.zapper.plugin.LoadedPlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SeasonUi(
    val season: Season,
    /** Titel zonder "(13 afleveringen)". */
    val label: String,
    val episodes: List<Episode> = emptyList(),
    /** Alle pagina's geladen. */
    val complete: Boolean = false,
    /** Aantal afleveringen volgens het platform (uit de titel), als dat gekend is. */
    val expectedCount: Int? = null,
    val watchedCount: Int = 0,
    val startedCount: Int = 0,
) {
    val total: Int get() = expectedCount ?: episodes.size
    val fullyWatched: Boolean get() = total > 0 && watchedCount >= total
}

enum class NextUpKind { RESUME, NEXT, START }

data class NextUp(val episode: Episode, val seasonIndex: Int, val kind: NextUpKind)

data class DetailState(
    val item: ItemEntity? = null,
    /** Dezelfde titel bij andere providers (na deduplicatie). */
    val alternatives: List<ItemEntity> = emptyList(),
    val description: String? = null,
    val seasons: List<SeasonUi> = emptyList(),
    val selectedSeason: Int = 0,
    /** Kijkstatus per playableId: platform + lokaal samengevoegd. */
    val watch: Map<String, WatchState> = emptyMap(),
    val nextUp: NextUp? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** In kijkvolgorde (Play zet de nieuwste aflevering vooraan). */
    val episodes: List<Episode> get() = inEpisodeOrder(seasons.getOrNull(selectedSeason)?.episodes.orEmpty())
}

/** Op afleveringsnummer als elke aflevering er een heeft, anders de volgorde van het platform. */
fun inEpisodeOrder(eps: List<Episode>): List<Episode> =
    if (eps.isNotEmpty() && eps.all { it.episodeNumber != null }) eps.sortedBy { it.episodeNumber } else eps

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(private val graph: AppGraph, itemKey: String) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var local: Map<String, ProgressEntity> = emptyMap()
    /** Kiest de gebruiker zelf een seizoen, dan springen we niet meer naar "volgende". */
    private var userPickedSeason = false

    init {
        viewModelScope.launch {
            // Niet in de index (bv. enkel in "Mijn lijst")? Dan met de gegevens uit Mijn lijst.
            val item = graph.dao.item(itemKey) ?: graph.dao.myListEntry(itemKey)?.toItem()
                ?: return@launch _state.update { it.copy(loading = false, error = "Titel niet gevonden") }
            val alternatives = graph.dao.sameTitle(item.dedupKey)
            _state.update { it.copy(item = item, alternatives = alternatives, description = item.description) }
            load(item)
        }
    }

    fun switchProvider(item: ItemEntity) {
        userPickedSeason = false
        _state.update { it.copy(item = item, seasons = emptyList(), selectedSeason = 0, nextUp = null, watch = emptyMap(), loading = true, error = null) }
        load(item)
    }

    fun selectSeason(index: Int) {
        val season = _state.value.seasons.getOrNull(index) ?: return
        userPickedSeason = true
        _state.update { it.copy(selectedSeason = index) }
        if (!season.complete) {
            val plugin = _state.value.item?.let { graph.plugins.get(it.providerId) } ?: return
            viewModelScope.launch { loadFull(plugin, index); recompute() }
        }
    }

    private fun load(item: ItemEntity) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (item.playableId != null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val plugin = graph.plugins.get(item.providerId)
                ?: return@launch _state.update { it.copy(loading = false, error = "Plugin ${item.providerId} niet geladen") }
            try {
                val details = plugin.provider.details(item.itemId)
                local = graph.dao.progressForItem(item.key).associateBy { it.playableId }
                _state.update {
                    it.copy(
                        seasons = details.seasons.map { s -> SeasonUi(s, seasonLabel(s.title), s.episodesPreview, expectedCount = expectedCount(s.title)) },
                        description = it.description ?: details.item.description,
                        loading = false,
                    )
                }
                loadAllSeasons(plugin)
            } catch (e: ProviderException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Kon details niet laden: ${e.message}") }
            }
        }
    }

    /**
     * Eerste pagina van elk seizoen parallel (voor de bekeken-tellers en "volgende aflevering"),
     * daarna het seizoen van de volgende aflevering volledig.
     */
    private suspend fun loadAllSeasons(plugin: LoadedPlugin) {
        val seasons = _state.value.seasons
        val gate = Semaphore(4)
        coroutineScope {
            seasons.take(MAX_SEASONS).indices.map { index ->
                async {
                    gate.withPermit {
                        runCatching { plugin.provider.episodes(seasons[index].season.id, null) }
                            .onSuccess { page -> updateSeason(index) { it.copy(episodes = page.items, complete = page.nextCursor == null) } }
                            .onFailure { e -> if (index == 0) _state.update { it.copy(error = "Afleveringen laden mislukt: ${e.message}") } }
                        if (index == 0) recompute()
                    }
                }
            }.awaitAll()
        }
        recompute()
        // De volgende aflevering kan op een latere pagina staan: dat seizoen volledig laden.
        val target = _state.value.nextUp?.seasonIndex ?: 0
        loadFull(plugin, target)
        val hint = continueHint()
        if (hint != null && _state.value.seasons.none { s -> s.episodes.any { it.playableId == hint } }) {
            // Hint van het platform niet gevonden in de eerste pagina's: zoek verder in de onvolledige seizoenen.
            for (i in _state.value.seasons.indices) {
                if (_state.value.seasons[i].complete) continue
                loadFull(plugin, i)
                if (_state.value.seasons[i].episodes.any { it.playableId == hint }) break
            }
        }
        recompute()
    }

    private suspend fun loadFull(plugin: LoadedPlugin, index: Int) {
        val season = _state.value.seasons.getOrNull(index) ?: return
        if (season.complete) return
        try {
            val all = mutableListOf<Episode>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = plugin.provider.episodes(season.season.id, cursor)
                all += page.items
                cursor = page.nextCursor
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            updateSeason(index) { it.copy(episodes = all, complete = true) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Afleveringen laden mislukt: ${e.message}") }
        }
    }

    private fun updateSeason(index: Int, f: (SeasonUi) -> SeasonUi) {
        _state.update { s -> s.copy(seasons = s.seasons.mapIndexed { i, season -> if (i == index) f(season) else season }) }
    }

    /** Na terugkeer uit de player: lokale voortgang opnieuw inlezen en "volgende" herberekenen. */
    fun refreshProgress() {
        val item = _state.value.item ?: return
        if (_state.value.seasons.isEmpty()) return
        viewModelScope.launch {
            local = graph.dao.progressForItem(item.key).associateBy { it.playableId }
            recompute()
        }
    }

    private var remoteHint: String? = null

    private suspend fun continueHint(): String? {
        val item = _state.value.item ?: return null
        remoteHint = graph.dao.remoteContinueForItem(item.key).firstOrNull()?.playableId
        return remoteHint
    }

    private suspend fun recompute() {
        if (remoteHint == null) continueHint()
        val s = _state.value
        val allEpisodes = s.seasons.flatMap { it.episodes }
        val watch = allEpisodes.mapNotNull { ep -> WatchState.of(ep, local[ep.playableId])?.let { ep.playableId to it } }.toMap()
        val seasons = s.seasons.map { season ->
            season.copy(
                watchedCount = season.episodes.count { watch[it.playableId]?.watched == true },
                startedCount = season.episodes.count { watch[it.playableId]?.started == true },
            )
        }
        val nextUp = nextUp(seasons, watch)
        _state.update {
            it.copy(
                seasons = seasons,
                watch = watch,
                nextUp = nextUp,
                selectedSeason = if (!userPickedSeason && nextUp != null) nextUp.seasonIndex else it.selectedSeason,
            )
        }
    }

    /**
     * Volgende aflevering, in deze volgorde:
     * 1. wat je net in Zapper keek (lokaal, recenter dan [ProgressSync.LOCAL_WINS_MS]);
     * 2. de "Verder kijken"-lijst van het platform;
     * 3. de laatst bekeken aflevering in volgorde: hervatten, of de aflevering erna;
     * 4. de eerste beschikbare aflevering.
     */
    private fun nextUp(seasons: List<SeasonUi>, watch: Map<String, WatchState>): NextUp? {
        val order = playOrder(seasons)
        if (order.isEmpty()) return null
        fun indexOf(playableId: String) = order.indexOfFirst { it.second.playableId == playableId }
        fun at(i: Int, kind: NextUpKind) = order.getOrNull(i)?.let { NextUp(it.second, it.first, kind) }
        fun after(i: Int): NextUp? =
            (i + 1 until order.size).firstOrNull { order[it].second.available }?.let { at(it, NextUpKind.NEXT) }
        fun from(i: Int): NextUp? {
            val w = watch[order[i].second.playableId]
            return if (w?.watched == true) after(i) else at(i, if (w?.started == true) NextUpKind.RESUME else NextUpKind.NEXT)
        }

        val now = System.currentTimeMillis()
        local.values.filter { now - it.updatedAt < ProgressSync.LOCAL_WINS_MS }.maxByOrNull { it.updatedAt }
            ?.let { indexOf(it.playableId) }?.takeIf { it >= 0 }?.let { i -> from(i)?.let { return it } }

        remoteHint?.let { indexOf(it) }?.takeIf { it >= 0 }?.let { i -> from(i)?.let { return it } }

        val lastStarted = order.indices.lastOrNull { watch[order[it].second.playableId]?.started == true }
        if (lastStarted != null) from(lastStarted)?.let { return it }

        return order.indices.firstOrNull { order[it].second.available }?.let { at(it, NextUpKind.START) }
    }

    /** (seizoenindex, aflevering) in kijkvolgorde: seizoen- en afleveringsnummer als die er zijn, anders de lijstvolgorde. */
    private fun playOrder(seasons: List<SeasonUi>): List<Pair<Int, Episode>> {
        val seasonNumbers = seasons.mapIndexed { i, s -> i to (SEASON_NUMBER.find(s.label)?.groupValues?.get(1)?.toIntOrNull() ?: s.episodes.firstNotNullOfOrNull { it.seasonNumber }) }
        val sortedSeasons = if (seasonNumbers.all { it.second != null }) seasonNumbers.sortedBy { it.second } else seasonNumbers
        return sortedSeasons.flatMap { (i, _) ->
            inEpisodeOrder(seasons[i].episodes).map { i to it }
        }
    }

    /** "Mijn lijst" van het platform; volgt de lokale tabel, dus ook wijzigingen van elders. */
    val inMyList = _state.flatMapLatest { s -> s.item?.let { graph.dao.isInMyList(it.key) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun supportsMyList(): Boolean =
        _state.value.item?.let { graph.plugins.get(it.providerId)?.provider?.info?.supportsMyList } == true

    fun toggleMyList() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val error = graph.myListSync.set(item, !inMyList.value)
            if (error != null) _state.update { it.copy(error = error) }
        }
    }

    /** Wat "Afspelen" doet: film/live direct, anders de volgende aflevering. */
    fun playTarget(): Pair<String, String>? {
        val s = _state.value
        val item = s.item ?: return null
        item.playableId?.let { return it to item.title }
        val ep = s.nextUp?.episode ?: return null
        return ep.playableId to playTitle(item.title, ep.title)
    }

    companion object {
        private const val MAX_SEASONS = 40
        private const val MAX_PAGES = 6
        private val EPISODE_COUNT = Regex("""\((\d+)\s+afleveringen?\)""", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER = Regex("""(?i)seizoen\s+(\d+)""")

        fun seasonLabel(title: String) = title.replace(EPISODE_COUNT, "").trim()
        fun expectedCount(title: String) = EPISODE_COUNT.find(title)?.groupValues?.get(1)?.toIntOrNull()
    }
}
