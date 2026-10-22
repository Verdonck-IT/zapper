package be.verdonckit.zapper.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import be.verdonckit.zapper.ui.theme.ZapperColors

/** Opties voor een kaart uit "Verder kijken" (OK ingedrukt houden). Terug sluit het menu. */
@Composable
fun ContinueMenu(
    tile: Tile,
    onOpenProgram: (() -> Unit)?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // Het menu opent terwijl OK nog ingedrukt is (lang indrukken). Die herhaalde druk en het loslaten
    // mogen niets activeren: pas een nieuwe druk op OK telt.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    if (armed || e.key !in CONFIRM_KEYS) return@onPreviewKeyEvent false
                    // Een nieuwe druk (geen herhaling) telt meteen; herhalingen en het loslaten van
                    // de lange druk die het menu opende, worden genegeerd.
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                        armed = true
                        return@onPreviewKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyUp) armed = true
                    true
                }
                .width(460.dp)
                .background(ZapperColors.Surface, RoundedCornerShape(12.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Een Dialog erft de tekstkleur van het thema niet: expliciet wit.
            Text(tile.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            tile.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ZapperColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            if (onOpenProgram != null) {
                Button(onClick = onOpenProgram, modifier = Modifier.focusRequester(first)) { Text("Naar programma") }
            }
            Button(
                onClick = onRemove,
                modifier = if (onOpenProgram == null) Modifier.focusRequester(first) else Modifier,
            ) { Text("Verwijderen uit Verder kijken") }
            OutlinedButton(onClick = onDismiss) { Text("Annuleren") }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusAfterLayout() }
}

private val CONFIRM_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
