package be.verdonckit.zapper.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Laat een lijst of raster [start]/[end] buiten de padding van de ouder uitsteken.
 * Geef dezelfde ruimte terug als `contentPadding`, dan blijven de kaarten op dezelfde plaats
 * staan, maar knipt de lijst de vergrote kaart met focus (en haar rand) niet meer af.
 */
fun Modifier.bleedHorizontal(start: Dp, end: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + s + e,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + s + e else constraints.maxWidth,
        ),
    )
    layout(placeable.width - s - e, placeable.height) { placeable.place(-s, 0) }
}
