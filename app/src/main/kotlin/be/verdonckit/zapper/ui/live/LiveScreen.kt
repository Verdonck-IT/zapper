package be.verdonckit.zapper.ui.live

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import be.verdonckit.zapper.graph
import kotlinx.coroutines.flow.map
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.components.BackToTop
import be.verdonckit.zapper.ui.components.CardBadge
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import be.verdonckit.zapper.ui.theme.ZapperColors

/** Alle live-zenders van alle providers, als kaarten met het zenderlogo. OK = meteen live kijken. */
@Composable
fun LiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    // Populairste zenders eerst (volgorde in categories.json), ongeacht de provider.
    val channels by remember {
        graph.dao.liveChannels().map { list -> list.sortedBy { graph.categoryMapper.liveRank(it.title) } }
    }.collectAsStateWithLifecycle(emptyList())
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }

    BackToTop(
        atTop = { firstFocused || channels.isEmpty() },
        toTop = {
            grid.scrollToItem(0)
            firstCard.requestFocusAfterLayout()
        },
        onBackAtTop = onBack,
    )

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 32.dp)) {
        Text("Live tv", style = MaterialTheme.typography.displaySmall, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            if (channels.isEmpty()) "Nog geen zenders. Log in bij je zenders via Instellingen." else "Kies een zender om live te kijken",
            style = MaterialTheme.typography.bodyLarge,
            color = ZapperColors.TextSecondary,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.key }) { index, ch ->
                // Bijna zwart met een vleugje merkkleur: zenderlogo's (vaak in de merkkleur zelf) blijven leesbaar.
                val brand = plugins[ch.providerId]?.provider?.info?.brandColor
                    ?.let { lerp(Color(it), Color.Black, 0.82f) } ?: ZapperColors.Surface
                Column {
                    ContentCard(
                        title = ch.title,
                        // Eigen zenderkaart als de plugin die levert (bv. Focus, WTV); anders het logo op de merkkleur.
                        imageUrls = listOf(ch.backdropUrl),
                        brandColor = brand,
                        logoUrl = ch.logoUrl,
                        providerLabel = null,
                        progress = null,
                        badge = CardBadge("●  LIVE", ZapperColors.Accent),
                        onClick = {
                            ch.playableId?.let {
                                context.startActivity(PlayerActivity.intent(context, ch.providerId, it, ch.title, ch.key, ch.logoUrl))
                            }
                        },
                        onFocus = {},
                        width = 0.dp,
                        showTitle = false,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstCard).onFocusChanged { firstFocused = it.isFocused }
                        } else {
                            Modifier
                        },
                    )
                    Text(
                        ch.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    plugins[ch.providerId]?.name?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = ZapperColors.TextSecondary)
                    }
                }
            }
        }
    }

    LaunchedEffect(channels.isNotEmpty()) {
        if (channels.isNotEmpty()) firstCard.requestFocusAfterLayout()
    }
}
