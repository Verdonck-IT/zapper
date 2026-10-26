package be.verdonckit.zapper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object ZapperColors {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1F1F1F)
    val Accent = Color(0xFFE50914)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val Success = Color(0xFF46D369)
    val Warning = Color(0xFFF5A623)
}

private val colors = darkColorScheme(
    primary = ZapperColors.Accent,
    onPrimary = Color.White,
    background = ZapperColors.Background,
    onBackground = ZapperColors.TextPrimary,
    surface = ZapperColors.Surface,
    onSurface = ZapperColors.TextPrimary,
    onSurfaceVariant = ZapperColors.TextSecondary,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ZapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
