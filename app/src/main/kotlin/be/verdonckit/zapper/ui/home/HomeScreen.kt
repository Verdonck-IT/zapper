package be.verdonckit.zapper.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import be.verdonckit.zapper.ui.components.DefaultScrollInside
import be.verdonckit.zapper.ui.components.PivotToTop
import be.verdonckit.zapper.ui.components.HeroBackdrop
import be.verdonckit.zapper.ui.components.HeroInfo
import be.verdonckit.zapper.ui.components.Sidebar
import be.verdonckit.zapper.ui.components.SidebarDestination
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import be.verdonckit.zapper.ui.theme.ZapperColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
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
    var menuFor by remember { mutableStateOf<Tile?>(null) }
    val message by vm.message.collectAsStateWithLifecycle()
    val compat by context.graph.plugins.compatibility.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Per kaart een FocusRequester, zodat de focus na het sluiten van het menu terugkeert waar je was.
    // De eerste kaart bovenaan heeft [firstCard] en staat hier bewust niet in: "Verder kijken" kan
    // herschikken, en dan zou een andere kaart via deze map ook firstCard krijgen.
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    var lastFocusedCard by remember { mutableStateOf<FocusRequester?>(null) }

    fun closeSidebar() {
        sidebarOpen = false
        scope.launch {
            val target = lastFocusedCard ?: firstCard
            target.requestFocusAfterLayout()
        }
    }

    // Terug: eerst naar de eerste kaart bovenaan; staat die al in focus, dan opent het menu (app sluit nooit).
    BackToTop(
        atTop = { firstCardFocused || state.rows.isEmpty() },
        toTop = {
            listState.scrollToItem(0)
            firstRowState.scrollToItem(0)
            firstCard.requestFocusAfterLayout { firstCardFocused }
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
                if (compat?.updateRequired == true) {
                    // Verplichte app-update: plugins vragen een nieuwere plugin-API (details en knop in Instellingen).
                    Text(
                        "⚠  App-update verplicht · zie Instellingen",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Black,
                        modifier = Modifier
                            .padding(start = 20.dp)
                            .background(ZapperColors.Warning, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("◀  menu", style = MaterialTheme.typography.labelMedium, color = ZapperColors.TextSecondary)
            }

            if (hero != null) {
                HeroInfo(
                    title = hero.title,
                    logoUrl = hero.logoUrl,
                    description = hero.description,
                    providerLabel = state.providerNames[hero.providerId],
                    // Vaste hoogte (rijen springen niet bij elke focuswissel), maar zonder lege band eronder.
                    modifier = Modifier.padding(start = 56.dp, top = 12.dp, bottom = 8.dp).height(184.dp),
                    descriptionLines = 2,
                )
            }

            if (!state.loading && state.rows.isEmpty()) {
                EmptyState { onNavigate(SidebarDestination.SETTINGS) }
            }

            PivotToTop(leading = 48.dp) { defaultScroll ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { rowIndex, row ->
                    val ownRowState = rememberLazyListState()
                    Column {
                        Row(Modifier.padding(start = 56.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text(row.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                            if (row.id == "continue" && row.tiles.any { it.removable }) {
                                Text(
                                    "   houd OK ingedrukt om te verwijderen",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ZapperColors.TextSecondary,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                        // ▲/▼ naar deze rij: terug naar de kaart waar je in deze rij stond, de eerste keer
                        // naar de eerste kaart (niet de kaart die toevallig het dichtst bij staat).
                        val rowFirst = if (rowIndex == 0) firstCard
                        else cardFocus.getOrPut("${row.id}/${row.tiles.first().id}") { FocusRequester() }
                        DefaultScrollInside(defaultScroll) {
                        LazyRow(
                            state = if (rowIndex == 0) firstRowState else ownRowState,
                            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.focusRestorer { rowFirst },
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
                                    onFocus = { focused = tile; lastFocusedCard = requester },
                                    onLongClick = if (row.id == "continue" && tile.removable) ({ menuFor = tile }) else null,
                                    modifier = Modifier
                                        .focusRequester(requester)
                                        .then(if (isFirst) Modifier.onFocusChanged { firstCardFocused = it.isFocused } else Modifier),
                                )
                            }
                        }
                        }
                    }
                }
            }
            }
        }

        message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(Color(0xE6202020), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(5_000)
                vm.clearMessage()
            }
        }

        menuFor?.let { tile ->
            ContinueMenu(
                tile = tile,
                onOpenProgram = tile.item?.let { item -> { menuFor = null; onOpen(item) } },
                onRemove = {
                    menuFor = null
                    vm.removeFromContinue(tile)
                    // Focus terug naar het begin van de rij; de kaart zelf verdwijnt.
                    scope.launch { firstCard.requestFocusAfterLayout() }
                },
                onDismiss = { menuFor = null },
            )
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
