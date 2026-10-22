package be.verdonckit.zapper.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import be.verdonckit.zapper.ui.theme.ZapperColors

enum class SidebarDestination(val label: String, val icon: String) {
    HOME("Home", "⌂"),
    SEARCH("Zoeken", "⌕"),
    MY_LIST("Mijn lijst", "＋"),
    LIVE("Live tv", "●"),
    SETTINGS("Instellingen", "⚙"),
}

/**
 * Menu links, over de inhoud heen. Opent met ◀ vanaf de linkerrand of met terug als er niets
 * meer terug te scrollen valt. ▶ of terug sluit het; de focus blijft in het menu zolang het open is.
 */
@Composable
fun Sidebar(
    open: Boolean,
    current: SidebarDestination,
    onSelect: (SidebarDestination) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = open) { onClose() }

    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        // Scrim: de inhoud blijft zichtbaar maar treedt terug.
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
    }
    AnimatedVisibility(
        open,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
    ) {
        val requesters = remember { SidebarDestination.entries.associateWith { FocusRequester() } }
        val items = SidebarDestination.entries
        Column(
            Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF101010))))
                .padding(start = 32.dp, end = 24.dp, top = 40.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { onClose(); true }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ZAPPER", style = MaterialTheme.typography.headlineSmall, color = ZapperColors.Accent)
            Spacer(Modifier.height(32.dp))
            items.forEachIndexed { index, dest ->
                val selected = dest == current
                Surface(
                    onClick = { onSelect(dest) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) Color(0x33FFFFFF) else Color.Transparent,
                        contentColor = if (selected) Color.White else ZapperColors.TextSecondary,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requesters.getValue(dest))
                        // Focus niet uit het menu laten ontsnappen aan de boven- of onderkant.
                        .onPreviewKeyEvent { e ->
                            e.type == KeyEventType.KeyDown && (
                                (e.key == Key.DirectionUp && index == 0) ||
                                    (e.key == Key.DirectionDown && index == items.lastIndex)
                                )
                        },
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dest.icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                        Text(
                            dest.label,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { requesters.getValue(current).requestFocusAfterLayout() }
    }
}
