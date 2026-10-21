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

/** Focus vragen nadat de lijst na het scrollen opnieuw opgemeten is (anders bestaat het element nog niet). */
suspend fun FocusRequester.requestFocusAfterLayout() {
    withFrameNanos { }
    runCatching { requestFocus() }
}
