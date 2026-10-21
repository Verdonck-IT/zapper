package be.verdonckit.zapper.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.UnifiedCategory
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer

data class SearchEntry(val item: ItemEntity, val categories: Set<String>, val normTitle: String, val normDescription: String)

data class SearchState(
    val query: String = "",
    val category: String? = null,
    /** Enkel categorieën waar iets in zit, in de volgorde van het startscherm. */
    val categories: List<UnifiedCategory> = emptyList(),
    val results: List<ItemEntity> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val providerColors: Map<String, Long> = emptyMap(),
    val loading: Boolean = true,
)

/**
 * Zoeken en bladeren in de lokale index (geen netwerk): zonder zoekterm de volledige
 * catalogus A–Z, eventueel per categorie; met zoekterm gerangschikt op relevantie.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(graph: AppGraph) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<String?>(null)
    private val allCategories = graph.categoryMapper.categories

    /** Eén kaart per titel (zelfde reeks bij meerdere zenders = één resultaat), met de categorieën van alle versies. */
    private val index = combine(graph.dao.allItems(), graph.dao.allItemCategories()) { items, cats ->
        val catsByItem = cats.groupBy({ it.itemKey }, { it.category })
        items.groupBy { it.dedupKey }.values.map { versions ->
            val best = versions.firstOrNull { it.backdropUrl != null || it.posterUrl != null } ?: versions.first()
            SearchEntry(
                item = best,
                categories = versions.flatMap { catsByItem[it.key].orEmpty() }.toSet(),
                normTitle = normalize(best.title),
                normDescription = normalize(best.description.orEmpty()),
            )
        }.sortedBy { sortKey(it.normTitle) }
    }

    val state: StateFlow<SearchState> = combine(
        index,
        query.debounce(150),
        category,
        graph.plugins.plugins,
        query,
    ) { entries, q, cat, plugins, rawQuery ->
        val present = entries.flatMap { it.categories }.toSet()
        val inCategory = if (cat == null) entries else entries.filter { cat in it.categories }
        SearchState(
            query = rawQuery,
            category = cat,
            categories = allCategories.filter { it.id in present },
            results = rank(inCategory, q).map { it.item },
            providerNames = plugins.mapValues { it.value.name },
            providerColors = plugins.mapValues { it.value.provider.info.brandColor },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun setQuery(q: String) { query.value = q }
    fun setCategory(id: String?) { category.value = id }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        /** "De ideale wereld" sorteert onder I, zoals in een A–Z-lijst. */
        private fun sortKey(norm: String) = norm.removePrefix("de ").removePrefix("het ").removePrefix("the ").removePrefix("een ")

        /**
         * 0 = titel begint met de zoekterm, 1 = elk woord begint een woord in de titel,
         * 2 = zoekterm ergens in de titel, 3 = alle woorden in de omschrijving.
         */
        fun rank(entries: List<SearchEntry>, rawQuery: String): List<SearchEntry> {
            val q = normalize(rawQuery)
            if (q.isEmpty()) return entries
            val words = q.split(' ')
            return entries.mapNotNull { e ->
                val titleWords = e.normTitle.split(' ')
                val score = when {
                    e.normTitle.startsWith(q) || sortKey(e.normTitle).startsWith(q) -> 0
                    words.all { w -> titleWords.any { it.startsWith(w) } } -> 1
                    e.normTitle.contains(q) -> 2
                    words.all { w -> e.normDescription.contains(w) } -> 3
                    else -> return@mapNotNull null
                }
                score to e
            }.sortedWith(compareBy({ it.first }, { sortKey(it.second.normTitle) })).map { it.second }
        }
    }
}
