package be.verdonckit.zapper.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import be.verdonckit.zapper.api.Episode
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.components.bleedHorizontal
import be.verdonckit.zapper.ui.components.CardBadge
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.components.HeroBackdrop
import be.verdonckit.zapper.ui.components.HeroInfo
import be.verdonckit.zapper.ui.home.HomeViewModel
import be.verdonckit.zapper.ui.theme.ZapperColors

@Composable
fun DetailScreen(itemKey: String, onOpenEpisode: (itemKey: String, episode: Episode) -> Unit) {
    val context = LocalContext.current
    val vm: DetailViewModel = viewModel(key = itemKey) { DetailViewModel(context.graph, itemKey) }
    val state by vm.state.collectAsStateWithLifecycle()
    val plugins by context.graph.plugins.plugins.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val episodeRow = rememberLazyListState()
    val item = state.item
    // Terug gaat hier meteen naar het vorige scherm (gewone navigatie), ook vanuit seizoenen of afleveringen.

    fun play(playableId: String, title: String, image: String?) {
        val i = item ?: return
        context.startActivity(PlayerActivity.intent(context, i.providerId, playableId, title, i.key, image ?: i.backdropUrl))
    }

    Box(Modifier.fillMaxSize()) {
        val brand = item?.let { plugins[it.providerId]?.provider?.info?.brandColor }?.let { Color(it) } ?: ZapperColors.Surface
        HeroBackdrop(listOf(item?.backdropUrl, item?.posterUrl), brand)

        Column(Modifier.fillMaxSize().padding(start = 56.dp, top = 48.dp)) {
            if (item != null) {
                HeroInfo(
                    title = item.title,
                    logoUrl = item.logoUrl,
                    description = state.description,
                    providerLabel = plugins[item.providerId]?.name,
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val target = vm.playTarget()
                val next = state.nextUp
                val label = when {
                    item?.playableId != null -> "▶  Afspelen"
                    next?.kind == NextUpKind.RESUME -> "▶  Verder kijken"
                    next?.kind == NextUpKind.NEXT -> "▶  Volgende aflevering"
                    else -> "▶  Afspelen"
                }
                Button(
                    onClick = { target?.let { play(it.first, it.second, next?.episode?.imageUrl) } },
                    enabled = target != null,
                    modifier = Modifier.focusRequester(playFocus),
                ) { Text(label) }

                if (vm.supportsMyList()) {
                    val inList by vm.inMyList.collectAsStateWithLifecycle()
                    OutlinedButton(onClick = vm::toggleMyList) { Text(if (inList) "✓  In mijn lijst" else "＋  Mijn lijst") }
                }

                // Zelfde titel bij een andere zender? Laat kiezen waar je kijkt.
                state.alternatives.filter { it.key != item?.key }.forEach { alt ->
                    OutlinedButton(onClick = { vm.switchProvider(alt) }) {
                        Text("Op ${plugins[alt.providerId]?.name ?: alt.providerId}")
                    }
                }

                if (next != null && item?.playableId == null) {
                    val w = state.watch[next.episode.playableId]
                    Text(
                        listOfNotNull(
                            listOfNotNull(HomeViewModel.episodeCode(next.episode.seasonNumber, next.episode.episodeNumber), next.episode.title).joinToString(" – "),
                            w?.takeIf { it.inProgress }?.let { HomeViewModel.remaining(it.positionMs, it.durationMs) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZapperColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = ZapperColors.Accent, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.loading) {
                Spacer(Modifier.height(12.dp))
                Text("Laden…", color = ZapperColors.TextSecondary)
            }

            if (state.seasons.size > 1) {
                Spacer(Modifier.height(20.dp))
                val seasonRow = rememberLazyListState()
                LaunchedEffect(state.selectedSeason) { seasonRow.animateScrollToItem(state.selectedSeason) }
                LazyRow(
                    state = seasonRow,
                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.bleedHorizontal(56.dp),
                ) {
                    itemsIndexed(state.seasons, key = { _, s -> s.season.id }) { index, season ->
                        val selected = index == state.selectedSeason
                        Button(
                            onClick = { vm.selectSeason(index) },
                            colors = if (selected) ButtonDefaults.colors() else ButtonDefaults.colors(
                                containerColor = ZapperColors.Surface,
                                contentColor = ZapperColors.TextSecondary,
                            ),
                        ) { Text(seasonChip(season), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            val episodes = state.episodes
            if (episodes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                // Volgende aflevering vooraan in beeld, zodat ↓ er meteen op landt.
                val nextIndex = state.nextUp?.takeIf { it.seasonIndex == state.selectedSeason }
                    ?.let { n -> episodes.indexOfFirst { it.playableId == n.episode.playableId } }?.takeIf { it >= 0 }
                LaunchedEffect(state.selectedSeason, nextIndex) { episodeRow.scrollToItem(nextIndex ?: 0) }
                LazyRow(
                    state = episodeRow,
                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.bleedHorizontal(56.dp),
                ) {
                    itemsIndexed(episodes, key = { _, ep -> ep.id }) { index, ep ->
                        EpisodeCard(
                            ep = ep,
                            state = state,
                            isNext = index == nextIndex,
                            brand = brand,
                            onClick = { item?.let { onOpenEpisode(it.key, ep) } },
                        )
                    }
                }
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.refreshProgress()
        onPauseOrDispose { }
    }

    LaunchedEffect(item?.key) {
        if (item != null) runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, state: DetailState, isNext: Boolean, brand: Color, onClick: () -> Unit) {
    val item = state.item
    val w = state.watch[ep.playableId]
    Column(Modifier.width(260.dp)) {
        ContentCard(
            title = listOfNotNull(ep.episodeNumber?.let { "$it." }, ep.title).joinToString(" "),
            // Aflevering: eigen still → beeld van het programma → gegenereerde kaart
            imageUrls = listOf(ep.imageUrl, item?.backdropUrl, item?.posterUrl),
            brandColor = brand,
            providerLabel = null,
            progress = when {
                w?.watched == true -> 1f
                w?.inProgress == true -> w.fraction
                else -> null
            },
            progressColor = if (w?.watched == true) ZapperColors.Success else ZapperColors.Accent,
            badge = when {
                isNext && w?.inProgress == true -> CardBadge("Verder kijken", ZapperColors.Accent)
                isNext -> CardBadge("Volgende", ZapperColors.Accent)
                w?.watched == true -> CardBadge("✓ Bekeken", Color(0xCC1E7A3C))
                else -> null
            },
            onClick = onClick,
            onFocus = {},
        )
        val meta = listOfNotNull(
            w?.takeIf { it.inProgress }?.let { HomeViewModel.remaining(it.positionMs, it.durationMs) },
            ep.durationSeconds?.takeIf { w?.inProgress != true }?.let { "${(it + 59) / 60} min" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = ZapperColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        ep.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = ZapperColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** "Reeks – Aflevering", of enkel de titel als die gelijk is (films). */
fun playTitle(itemTitle: String?, episodeTitle: String): String =
    if (itemTitle == null || itemTitle.equals(episodeTitle, ignoreCase = true)) episodeTitle else "$itemTitle – $episodeTitle"

/** "Seizoen 1 ✓" als alles bekeken is, "Seizoen 2 · 5/13" als je begonnen bent. */
private fun seasonChip(s: SeasonUi): String = when {
    s.fullyWatched -> "${s.label}  ✓"
    s.startedCount > 0 && (s.complete || s.expectedCount != null) -> "${s.label} · ${s.watchedCount}/${s.total}"
    s.startedCount > 0 -> "${s.label} · ${s.watchedCount} bekeken"
    else -> s.label
}
