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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.components.ContentCard
import be.verdonckit.zapper.ui.components.HeroBackdrop
import be.verdonckit.zapper.ui.components.HeroInfo
import be.verdonckit.zapper.ui.theme.ZapperColors
import androidx.compose.ui.graphics.Color

@Composable
fun DetailScreen(itemKey: String) {
    val context = LocalContext.current
    val vm: DetailViewModel = viewModel(key = itemKey) { DetailViewModel(context.graph, itemKey) }
    val state by vm.state.collectAsStateWithLifecycle()
    val plugins by context.graph.plugins.plugins.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val item = state.item

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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val target = vm.playTarget()
                val resuming = target != null && state.progress.containsKey(target.first)
                Button(
                    onClick = { target?.let { play(it.first, it.second, null) } },
                    enabled = target != null,
                    modifier = Modifier.focusRequester(playFocus),
                ) { Text(if (resuming) "▶  Hervatten" else "▶  Afspelen") }

                // Zelfde titel bij een andere zender? Laat kiezen waar je kijkt.
                state.alternatives.filter { it.key != item?.key }.forEach { alt ->
                    OutlinedButton(onClick = { vm.switchProvider(alt) }) {
                        Text("Op ${plugins[alt.providerId]?.name ?: alt.providerId}")
                    }
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(state.seasons, key = { _, s -> s.id }) { index, season ->
                        val selected = index == state.selectedSeason
                        Button(
                            onClick = { vm.selectSeason(index) },
                            colors = if (selected) ButtonDefaults.colors() else ButtonDefaults.colors(
                                containerColor = ZapperColors.Surface,
                                contentColor = ZapperColors.TextSecondary,
                            ),
                        ) { Text(season.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            if (state.episodes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(end = 56.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.episodes, key = { it.id }) { ep ->
                        val p = state.progress[ep.playableId]
                        Column(Modifier.width(260.dp)) {
                            ContentCard(
                                title = listOfNotNull(ep.episodeNumber?.let { "$it." }, ep.title).joinToString(" "),
                                // Aflevering: eigen still → beeld van het programma → gegenereerde kaart
                                imageUrls = listOf(ep.imageUrl, item?.backdropUrl, item?.posterUrl),
                                brandColor = brand,
                                providerLabel = null,
                                progress = p?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else null },
                                onClick = { play(ep.playableId, "${item?.title} – ${ep.title}", ep.imageUrl) },
                                onFocus = {},
                            )
                            ep.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ZapperColors.TextSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
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
