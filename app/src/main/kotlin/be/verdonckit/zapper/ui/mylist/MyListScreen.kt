package be.verdonckit.zapper.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.ui.components.BackToTop
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import be.verdonckit.zapper.ui.theme.ZapperColors
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** "Mijn lijst" van alle zenders samen, als raster. Verwijderen kan op de detailpagina. */
@Composable
fun MyListScreen(onOpen: (ItemEntity) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val entries by remember { graph.dao.myList() }.collectAsStateWithLifecycle(emptyList())
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<ItemEntity?>(null) }
    val scope = rememberCoroutineScope()

    BackToTop(
        atTop = { firstFocused || entries.isEmpty() },
        toTop = {
            grid.scrollToItem(0)
            firstCard.requestFocusAfterLayout()
        },
        onBackAtTop = onBack,
    )

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 32.dp)) {
        Text("Mijn lijst", style = MaterialTheme.typography.displaySmall, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                entries.isEmpty() -> "Nog niets bewaard. Kies op een programma \"+ Mijn lijst\" om het hier terug te vinden."
                focused != null -> listOfNotNull(focused?.title, focused?.description).joinToString(" — ")
                else -> "${entries.size} titels, van alle zenders samen"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = ZapperColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = grid,
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries, key = { _, e -> e.key }) { index, e ->
                val item = e.toItem()
                ContentCard(
                    title = e.title,
                    imageUrls = listOf(e.backdropUrl, e.posterUrl),
                    brandColor = plugins[e.providerId]?.provider?.info?.brandColor?.let { Color(it) } ?: ZapperColors.Surface,
                    logoUrl = e.logoUrl,
                    providerLabel = plugins[e.providerId]?.name,
                    progress = null,
                    onClick = { onOpen(item) },
                    onFocus = { focused = item },
                    width = 0.dp,
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstCard).onFocusChanged { firstFocused = it.isFocused }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        scope.launch { graph.myListSync.refresh() }
        onPauseOrDispose { }
    }

    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) firstCard.requestFocusAfterLayout()
    }
}
