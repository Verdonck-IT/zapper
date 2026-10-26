package be.verdonckit.zapper.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.launch

/**
 * Terugknop zoals op Netflix TV: eerst terug naar boven (scrollen + focus op het eerste
 * element), pas daarna [onBackAtTop]. Op het startscherm is dat een no-op, zodat terug
 * de app nooit sluit.
 */
@Composable
fun BackToTop(atTop: () -> Boolean, toTop: suspend () -> Unit, onBackAtTop: () -> Unit) {
    val scope = rememberCoroutineScope()
    BackHandler {
        if (atTop()) onBackAtTop() else scope.launch { toTop() }
    }
}

/**
 * Focus vragen nadat de lijst na het scrollen opnieuw opgemeten is (anders bestaat het element nog niet).
 * Met [isFocused]: enkele frames opnieuw proberen, want na een grote scrollsprong staat het element er
 * soms pas later en valt de focus intussen op de dichtstbijzijnde kaart.
 */
suspend fun FocusRequester.requestFocusAfterLayout(isFocused: (() -> Boolean)? = null) {
    repeat(if (isFocused == null) 1 else 10) {
        withFrameNanos { }
        runCatching { requestFocus() }
        if (isFocused == null || isFocused()) return
    }
}
