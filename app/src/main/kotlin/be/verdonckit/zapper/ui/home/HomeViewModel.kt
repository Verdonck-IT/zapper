package be.verdonckit.zapper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.verdonckit.zapper.AppGraph
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.ProgressEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    val resume: ProgressEntity? = null,
)

data class HomeRow(val id: String, val title: String, val tiles: List<Tile>)

data class HomeState(
    val rows: List<HomeRow> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val providerColors: Map<String, Long> = emptyMap(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(graph: AppGraph) : ViewModel() {

    private val dao = graph.dao
    private val categories = graph.categoryMapper.categories

    private val categoryRows = dao.categoriesWithContent().flatMapLatest { present ->
        val ordered = categories.filter { it.id in present }
        if (ordered.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(ordered.map { cat -> dao.rowFor(cat.id).map { items -> HomeRow(cat.id, cat.name, items.map(::tile)) } }) { it.toList() }
        }
    }

    private val continueRow = dao.continueWatching().map { list ->
        HomeRow(
            id = "continue",
            title = "Verder kijken",
            tiles = list.map { p ->
                // Still van de aflevering → beelden van het programma → titel-art → titelkaart
                val item = p.itemKey?.let { dao.item(it) }
                Tile(
                    id = "resume:${p.playableKey}",
                    title = p.title,
                    imageUrls = listOf(p.imageUrl, item?.backdropUrl, item?.posterUrl),
                    backdropUrls = listOf(item?.backdropUrl, p.imageUrl, item?.posterUrl),
                    logoUrl = item?.logoUrl,
                    description = null,
                    providerId = p.providerId,
                    progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null,
                    resume = p,
                )
            },
        )
    }

    val state: StateFlow<HomeState> = combine(continueRow, categoryRows, graph.plugins.plugins) { cont, rows, plugins ->
        HomeState(
            rows = (listOf(cont) + rows).filter { it.tiles.isNotEmpty() },
            providerNames = plugins.mapValues { it.value.name },
            providerColors = plugins.mapValues { it.value.provider.info.brandColor },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

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
}
