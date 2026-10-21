package be.verdonckit.zapper.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.theme.ZapperColors

/**
 * Zoeken en bladeren:
 * - zoekbalk bovenaan (OK opent het toetsenbord, "Zoeken" op het toetsenbord springt naar de resultaten);
 * - categorieën als filter ("Alles" = volledige catalogus);
 * - raster met alle titels A–Z, of gerangschikt op relevantie zodra je typt.
 */
@Composable
fun SearchScreen(onOpen: (ItemEntity) -> Unit) {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel { SearchViewModel(context.graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val grid = rememberLazyGridState()
    var focused by remember { mutableStateOf<ItemEntity?>(null) }

    // Nieuwe zoekterm of filter: terug naar boven.
    LaunchedEffect(state.query, state.category) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 32.dp)) {
        SearchField(
            value = state.query,
            onValueChange = vm::setQuery,
            onSearch = { focusManager.moveFocus(FocusDirection.Down) },
            focusRequester = fieldFocus,
        )

        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            item(key = "all") {
                FilterChip("Alles", selected = state.category == null) { vm.setCategory(null) }
            }
            items(state.categories, key = { it.id }) { cat ->
                FilterChip(cat.name, selected = state.category == cat.id) { vm.setCategory(cat.id) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.loading -> "Laden…"
                    state.results.isEmpty() && state.query.isNotBlank() -> "Niets gevonden voor \"${state.query}\""
                    state.results.isEmpty() -> "Nog geen inhoud. Log in bij je zenders via Instellingen."
                    state.query.isBlank() -> "${state.results.size} titels · A–Z"
                    else -> "${state.results.size} resultaten"
                },
                style = MaterialTheme.typography.titleMedium,
                color = ZapperColors.TextSecondary,
            )
            focused?.let { f ->
                Text(
                    "   ${f.title}${f.description?.let { " — $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZapperColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = grid,
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(state.results, key = { it.key }) { item ->
                ContentCard(
                    title = item.title,
                    imageUrls = listOf(item.backdropUrl, item.posterUrl),
                    brandColor = state.providerColors[item.providerId]?.let { Color(it) } ?: ZapperColors.Surface,
                    logoUrl = item.logoUrl,
                    providerLabel = state.providerNames[item.providerId],
                    progress = null,
                    onClick = { onOpen(item) },
                    onFocus = { focused = item },
                    width = 0.dp,
                )
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, focusRequester: FocusRequester) {
    var hasFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth(0.6f)
            .background(ZapperColors.Surface, shape)
            .border(BorderStroke(2.dp, if (hasFocus) Color.White else Color(0x33FFFFFF)), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text("🔍  Zoek een reeks, film of zender…", style = MaterialTheme.typography.bodyLarge, color = ZapperColors.TextSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(ZapperColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { hasFocus = it.isFocused },
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (selected) ButtonDefaults.colors() else ButtonDefaults.colors(
            containerColor = ZapperColors.Surface,
            contentColor = ZapperColors.TextSecondary,
        ),
    ) { Text(label, maxLines = 1) }
}
