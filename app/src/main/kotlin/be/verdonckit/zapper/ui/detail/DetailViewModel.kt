package be.verdonckit.zapper.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.api.ProviderException
import be.verdonckit.zapper.api.Season
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.ProgressEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val item: ItemEntity? = null,
    /** Dezelfde titel bij andere providers (na deduplicatie). */
    val alternatives: List<ItemEntity> = emptyList(),
    val description: String? = null,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int = 0,
    val episodes: List<Episode> = emptyList(),
    val progress: Map<String, ProgressEntity> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)

class DetailViewModel(private val graph: AppGraph, itemKey: String) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()
    private var episodesJob: Job? = null

    init {
        viewModelScope.launch {
            val item = graph.dao.item(itemKey) ?: return@launch _state.update { it.copy(loading = false, error = "Titel niet gevonden") }
            val alternatives = graph.dao.sameTitle(item.dedupKey)
            _state.update { it.copy(item = item, alternatives = alternatives, description = item.description) }
            loadDetails(item)
        }
    }

    fun switchProvider(item: ItemEntity) {
        _state.update { it.copy(item = item, seasons = emptyList(), episodes = emptyList(), selectedSeason = 0, loading = true, error = null) }
        viewModelScope.launch { loadDetails(item) }
    }

    fun selectSeason(index: Int) {
        val season = _state.value.seasons.getOrNull(index) ?: return
        _state.update { it.copy(selectedSeason = index) }
        loadEpisodes(season)
    }

    private suspend fun loadDetails(item: ItemEntity) {
        if (item.playableId != null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val plugin = graph.plugins.get(item.providerId)
            ?: return _state.update { it.copy(loading = false, error = "Plugin ${item.providerId} niet geladen") }
        try {
            val details = plugin.provider.details(item.itemId)
            _state.update {
                it.copy(
                    seasons = details.seasons,
                    description = it.description ?: details.item.description,
                    loading = false,
                )
            }
            details.seasons.firstOrNull()?.let(::loadEpisodes)
        } catch (e: ProviderException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Kon details niet laden: ${e.message}") }
        }
    }

    private fun loadEpisodes(season: Season) {
        val item = _state.value.item ?: return
        val plugin = graph.plugins.get(item.providerId) ?: return
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _state.update { it.copy(episodes = season.episodesPreview, error = null) }
            try {
                val all = mutableListOf<Episode>()
                var cursor: String? = null
                var pages = 0
                do {
                    val page = plugin.provider.episodes(season.id, cursor)
                    all += page.items
                    cursor = page.nextCursor
                    pages++
                } while (cursor != null && pages < 5)
                val progress = graph.dao.progressFor(item.providerId, all.map { it.playableId })
                _state.update { s -> s.copy(episodes = all, progress = progress.associateBy { it.playableId }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Afleveringen laden mislukt: ${e.message}") }
            }
        }
    }

    /** Na terugkeer uit de player: voortgangsbalken en "Hervatten" bijwerken. */
    fun refreshProgress() {
        val s = _state.value
        val item = s.item ?: return
        if (s.episodes.isEmpty()) return
        viewModelScope.launch {
            val progress = graph.dao.progressFor(item.providerId, s.episodes.map { it.playableId })
            _state.update { it.copy(progress = progress.associateBy { p -> p.playableId }) }
        }
    }

    /** Wat "Afspelen" doet: hervatten waar je zat, anders de eerste beschikbare aflevering. */
    fun playTarget(): Pair<String, String>? {
        val s = _state.value
        val item = s.item ?: return null
        item.playableId?.let { return it to item.title }
        val resumed = s.episodes.filter { s.progress.containsKey(it.playableId) }
            .maxByOrNull { s.progress[it.playableId]!!.updatedAt }
        val ep = resumed ?: s.episodes.firstOrNull { it.available } ?: return null
        return ep.playableId to "${item.title} – ${ep.title}"
    }
}
