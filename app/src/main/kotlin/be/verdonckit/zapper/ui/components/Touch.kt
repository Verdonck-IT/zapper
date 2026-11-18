package be.verdonckit.zapper.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton

/**
 * tv-material (Card, Surface, Button) reageert enkel op de OK-toets van de afstandsbediening,
 * niet op aanraking. Deze modifier voegt tikken (en lang indrukken) toe, zodat de app ook op
 * een tablet werkt. Op TV komen er geen aanraakgebeurtenissen, dus daar verandert niets.
 * Slepen blijft van de lijst: zodra de vinger beweegt, scrolt de lijst en telt het niet als tik.
 */
fun Modifier.touchClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = if (!enabled) this else composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    pointerInput(hasLong) {
        detectTapGestures(
            onLongPress = if (hasLong) ({ longClick?.invoke() }) else null,
            onTap = { click() },
        )
    }
}

/** Toestel met touchscreen (tablet/gsm); Android TV's melden dat niet. */
@Composable
fun isTouchDevice(): Boolean {
    val context = LocalContext.current
    return remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
}

/** tv-material [TvButton] die ook op aanraking reageert. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier.touchClickable(enabled, onClick = onClick),
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

/** tv-material [TvOutlinedButton] die ook op aanraking reageert. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvOutlinedButton(
        onClick = onClick,
        modifier = modifier.touchClickable(enabled, onClick = onClick),
        enabled = enabled,
        colors = OutlinedButtonDefaults.colors(),
        content = content,
    )
}

/**
 * Tablet: ronde ←-knop linksboven op elk scherm zonder menu (zoals Netflix). Enkel aanraakbaar,
 * nooit focusbaar: op TV bestaat hij niet en de D-pad kan er dus ook niet op landen.
 */
@Composable
fun TouchBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .touchClickable(onClick = onClick)
            .semantics { contentDescription = "Terug" },
        contentAlignment = Alignment.Center,
    ) {
        Text("←", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    }
}
