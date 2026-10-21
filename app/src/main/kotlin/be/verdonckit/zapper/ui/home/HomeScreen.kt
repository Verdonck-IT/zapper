package be.verdonckit.zapper.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.components.BackToTop
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.components.HeroBackdrop
import be.verdonckit.zapper.ui.components.HeroInfo
import be.verdonckit.zapper.ui.components.Sidebar
import be.verdonckit.zapper.ui.components.SidebarDestination
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import be.verdonckit.zapper.ui.theme.ZapperColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpen: (ItemEntity) -> Unit, onNavigate: (SidebarDestination) -> Unit) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel { HomeViewModel(context.graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<Tile?>(null) }
    val firstCard = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val firstRowState = rememberLazyListState()
    var firstCardFocused by remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Per kaart een FocusRequester, zodat de focus na het sluiten van het menu terugkeert waar je was.
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    var lastFocusedCard by remember { mutableStateOf<String?>(null) }

    fun closeSidebar() {
        sidebarOpen = false
        scope.launch {
            val target = lastFocusedCard?.let { cardFocus[it] } ?: firstCard
            target.requestFocusAfterLayout()
        }
    }

    // Terug: eerst naar de eerste kaart bovenaan; staat die al in focus, dan opent het menu (app sluit nooit).
    BackToTop(
        atTop = { firstCardFocused || state.rows.isEmpty() },
        toTop = {
            listState.scrollToItem(0)
            firstRowState.scrollToItem(0)
            firstCard.requestFocusAfterLayout()
        },
        onBackAtTop = { sidebarOpen = true },
    )

    val hero = focused ?: state.rows.firstOrNull()?.tiles?.firstOrNull()

    fun activate(tile: Tile) {
        // Ook "Verder kijken" opent eerst het programma (met de volgende aflevering klaar);
        // enkel als het programma niet in de index zit, spelen we meteen af.
        val play = tile.play
        when {
            tile.item != null -> onOpen(tile.item)
            play != null -> context.startActivity(
                PlayerActivity.intent(context, play.providerId, play.playableId, play.title, play.itemKey, play.imageUrl),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero?.backdropUrls.orEmpty(), state.brandColor(hero?.providerId))

        Column(
            Modifier
                .fillMaxSize()
                // ◀ op de linkerrand (niets meer links om naartoe te gaan): menu openen.
                .onPreviewKeyEvent { e ->
                    if (sidebarOpen || e.type != KeyEventType.KeyDown || e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    if (!focusManager.moveFocus(FocusDirection.Left)) sidebarOpen = true
                    true
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ZAPPER", style = MaterialTheme.typography.headlineSmall, color = ZapperColors.Accent)
                Spacer(Modifier.weight(1f))
                Text("◀  menu", style = MaterialTheme.typography.labelMedium, color = ZapperColors.TextSecondary)
            }

            if (hero != null) {
                HeroInfo(
                    title = hero.title,
                    logoUrl = hero.logoUrl,
                    description = hero.description,
                    providerLabel = state.providerNames[hero.providerId],
                    modifier = Modifier.padding(start = 56.dp, top = 24.dp).height(220.dp),
                )
            }

            if (!state.loading && state.rows.isEmpty()) {
                EmptyState { onNavigate(SidebarDestination.SETTINGS) }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { rowIndex, row ->
                    val ownRowState = rememberLazyListState()
                    Column {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 56.dp, bottom = 10.dp),
                        )
                        LazyRow(
                            state = if (rowIndex == 0) firstRowState else ownRowState,
                            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(row.tiles, key = { it.id }) { tile ->
                                val isFirst = rowIndex == 0 && tile == row.tiles.first()
                                val cardKey = "${row.id}/${tile.id}"
                                val requester = if (isFirst) firstCard else cardFocus.getOrPut(cardKey) { FocusRequester() }
                                ContentCard(
                                    title = tile.title,
                                    imageUrls = tile.imageUrls,
                                    brandColor = state.brandColor(tile.providerId),
                                    logoUrl = tile.logoUrl,
                                    providerLabel = state.providerNames[tile.providerId],
                                    progress = tile.progress,
                                    onClick = { activate(tile) },
                                    onFocus = { focused = tile; lastFocusedCard = cardKey },
                                    modifier = Modifier
                                        .focusRequester(requester)
                                        .then(if (isFirst) Modifier.onFocusChanged { firstCardFocused = it.isFocused } else Modifier),
                                )
                                if (isFirst) cardFocus[cardKey] = firstCard
                            }
                        }
                    }
                }
            }
        }

        Sidebar(
            open = sidebarOpen,
            current = SidebarDestination.HOME,
            onSelect = { dest ->
                if (dest == SidebarDestination.HOME) closeSidebar() else { sidebarOpen = false; onNavigate(dest) }
            },
            onClose = ::closeSidebar,
        )
    }

    LifecycleResumeEffect(Unit) {
        vm.refreshContinue()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.rows.isNotEmpty()) {
        if (state.rows.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }
}

@Composable
private fun EmptyState(onSettings: () -> Unit) {
    Column(Modifier.padding(56.dp)) {
        Text(
            "Nog geen inhoud. Log in bij je zenders via Instellingen; de catalogus wordt daarna automatisch opgebouwd.",
            style = MaterialTheme.typography.bodyLarge,
            color = ZapperColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSettings) { Text("Naar instellingen") }
    }
}

private fun HomeState.brandColor(providerId: String?): Color =
    providerId?.let { providerColors[it] }?.let { Color(it) } ?: ZapperColors.Surface
