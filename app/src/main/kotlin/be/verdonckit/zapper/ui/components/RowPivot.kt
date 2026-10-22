package be.verdonckit.zapper.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Zoals Netflix TV: de rij met de focus schuift altijd helemaal naar boven in de lijst.
 * Zo blijft er nooit een half afgesneden rij boven staan (die als een harde horizontale
 * snede door het hoofdbeeld liep).
 */
@OptIn(ExperimentalFoundationApi::class)
private class TopPivot(private val leadingPx: Float) : BringIntoViewSpec {
    // Compose brengt de kaart met de focus in beeld; [leadingPx] laat de rijtitel erboven zichtbaar.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - leadingPx
}

/** Het standaard scrollgedrag van buiten de lijst, om het in de rijen te herstellen. */
@OptIn(ExperimentalFoundationApi::class)
class DefaultScroll internal constructor(internal val spec: BringIntoViewSpec)

/** Verticale lijst met [TopPivot]; de rijen erin krijgen het standaardgedrag terug via [DefaultScrollInside]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotToTop(leading: Dp, content: @Composable (default: DefaultScroll) -> Unit) {
    val default = DefaultScroll(LocalBringIntoViewSpec.current)
    val leadingPx = with(LocalDensity.current) { leading.toPx() }
    val pivot = remember(leadingPx) { TopPivot(leadingPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) { content(default) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DefaultScrollInside(default: DefaultScroll, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides default.spec, content = content)
}
