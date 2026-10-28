package be.verdonckit.zapper.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.data.WatchState
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.components.HeroBackdrop
import be.verdonckit.zapper.ui.components.HeroInfo
import be.verdonckit.zapper.ui.home.HomeViewModel
import be.verdonckit.zapper.ui.theme.ZapperColors
import kotlinx.coroutines.launch

/**
 * Info over één aflevering vóór het afspelen. "Afspelen"/"Verder kijken" heeft meteen de focus,
 * dus OK speelt direct af; terug brengt je naar de reeks.
 */
@Composable
fun EpisodeScreen(itemKey: String, playableId: String, onGone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val episode = remember(itemKey, playableId) { graph.episodeSelection["$itemKey|$playableId"] }
    if (episode == null) {
        // Bv. na het herstarten van de app: terug naar de reeks.
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    var item by remember { mutableStateOf<ItemEntity?>(null) }
    var watch by remember { mutableStateOf<WatchState?>(null) }
    val playFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(itemKey, playableId) {
        // Ook na terugkeer uit de player: voortgang opnieuw inlezen.
        val job = scope.launch {
            item = graph.dao.item(itemKey)
            watch = WatchState.of(episode, graph.dao.progress("${itemKey.substringBefore(':')}:$playableId"))
        }
        onPauseOrDispose { job.cancel() }
    }

    fun play(fromStart: Boolean) {
        val i = item
        val providerId = i?.providerId ?: itemKey.substringBefore(':')
        context.startActivity(
            PlayerActivity.intent(
                context, providerId, episode.playableId, playTitle(i?.title, episode.title), itemKey,
                episode.imageUrl ?: i?.backdropUrl, fromStart = fromStart,
            ),
        )
    }

    Box(Modifier.fillMaxSize()) {
        val brand = item?.let { plugins[it.providerId]?.provider?.info?.brandColor }?.let { Color(it) } ?: ZapperColors.Surface
        HeroBackdrop(listOf(episode.imageUrl, item?.backdropUrl, item?.posterUrl), brand)

        Column(Modifier.fillMaxSize().padding(start = 56.dp, top = 48.dp, end = 56.dp)) {
            HeroInfo(
                title = item?.title.orEmpty(),
                logoUrl = item?.logoUrl,
                description = null,
                providerLabel = item?.let { plugins[it.providerId]?.name },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                HomeViewModel.episodeLabel(episode.seasonNumber, episode.episodeNumber, episode.title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(720.dp),
            )

            val w = watch
            val meta = listOfNotNull(
                episode.durationSeconds?.let { "${(it + 59) / 60} min" },
                when {
                    w?.watched == true -> "✓ Bekeken"
                    w?.inProgress == true -> HomeViewModel.remaining(w.positionMs, w.durationMs)
                    else -> null
                },
                if (!episode.available) "Niet meer beschikbaar" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, style = MaterialTheme.typography.titleMedium, color = if (w?.watched == true) ZapperColors.Success else ZapperColors.TextSecondary)
            }
            if (w?.inProgress == true) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(360.dp).height(4.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(w.fraction ?: 0f).background(ZapperColors.Accent))
                }
            }

            episode.description?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZapperColors.TextSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(720.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { play(fromStart = false) },
                    enabled = episode.available,
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Text(
                        when {
                            w?.inProgress == true -> "▶  Verder kijken"
                            w?.watched == true -> "▶  Opnieuw bekijken"
                            else -> "▶  Afspelen"
                        },
                    )
                }
                if (w?.inProgress == true) {
                    OutlinedButton(onClick = { play(fromStart = true) }) { Text("Vanaf het begin") }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
}
