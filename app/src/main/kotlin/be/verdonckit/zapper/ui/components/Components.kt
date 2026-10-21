package be.verdonckit.zapper.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import be.verdonckit.zapper.ui.theme.ZapperColors
import coil3.compose.AsyncImage

/**
 * Beeld met fallback-keten: probeert elke URL op volgorde (ook als een URL bestaat maar
 * een 404 of kapot beeld geeft) en tekent als laatste redmiddel [fallback].
 * De merkkleur-achtergrond staat er altijd onder, dus ook tijdens het laden is er nooit zwart.
 */
@Composable
fun ArtworkImage(
    urls: List<String?>,
    brandColor: Color,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    onAllFailed: () -> Unit = {},
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val candidates = remember(urls) { urls.filterNotNull().filter { it.isNotBlank() }.distinct() }
    var index by remember(candidates) { mutableIntStateOf(0) }

    Box(modifier.background(brandGradient(brandColor))) {
        if (index < candidates.size) {
            AsyncImage(
                model = candidates[index],
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                onError = {
                    index++
                    if (index >= candidates.size) onAllFailed()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            fallback()
        }
    }
}

fun brandGradient(brand: Color): Brush = Brush.linearGradient(
    0f to lerp(brand, Color.Black, 0.35f),
    1f to lerp(brand, ZapperColors.Background, 0.85f),
)

/** Label linksboven op een kaart, bv. "✓ Bekeken" of "Volgende". */
data class CardBadge(val text: String, val color: Color)

/** 16:9-kaart zoals in Netflix-rijen: beeld, provider-badge, voortgangsbalk. */
@Composable
fun ContentCard(
    title: String,
    imageUrls: List<String?>,
    brandColor: Color,
    providerLabel: String?,
    logoUrl: String? = null,
    progress: Float?,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 260.dp,
    badge: CardBadge? = null,
    progressColor: Color = ZapperColors.Accent,
) {
    val hasCandidates = imageUrls.any { !it.isNullOrBlank() }
    var imageFailed by remember(imageUrls) { mutableStateOf(!hasCandidates) }

    Card(
        onClick = onClick,
        modifier = modifier
            .then(if (width > 0.dp) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(6.dp)),
        ),
        colors = CardDefaults.colors(containerColor = ZapperColors.Surface),
    ) {
        Box(Modifier.fillMaxSize()) {
            ArtworkImage(
                urls = imageUrls,
                brandColor = brandColor,
                onAllFailed = { imageFailed = true },
                modifier = Modifier.fillMaxSize(),
            ) {
                var logoFailed by remember(logoUrl) { mutableStateOf(logoUrl.isNullOrBlank()) }
                if (!logoFailed) {
                    // Voorlaatste niveau: titel-art (transparant) passend op de merkkleur.
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        onError = { logoFailed = true },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
                    )
                } else {
                    // Laatste niveau: gegenereerde titelkaart in de kleur van de zender.
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
            }
            if (!imageFailed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (providerLabel != null) {
                Text(
                    text = providerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (badge != null) {
                Text(
                    text = badge.text,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(badge.color)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(progressColor))
                }
            }
        }
    }
}

/**
 * Groot achtergrondbeeld rechtsboven met verloop naar links en onder, zoals op Netflix TV.
 * Zonder bruikbaar beeld: een zachte gloed in de merkkleur, zodat het scherm niet leeg oogt.
 */
@Composable
fun HeroBackdrop(urls: List<String?>, brandColor: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = urls, animationSpec = tween(400), label = "hero") { candidates ->
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .fillMaxHeight(0.75f)
                        .align(Alignment.TopEnd)
                        .background(
                            Brush.radialGradient(
                                listOf(brandColor.copy(alpha = 0.55f), Color.Transparent),
                                radius = 1400f,
                            ),
                        ),
                ) {
                    if (candidates.any { !it.isNullOrBlank() }) {
                        ArtworkImage(urls = candidates, brandColor = Color.Transparent, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to ZapperColors.Background,
                    0.32f to ZapperColors.Background,
                    0.62f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.35f to Color.Transparent,
                    0.72f to ZapperColors.Background,
                ),
            ),
        )
    }
}

/** Titel-art als die er is én laadt; anders de titel in grote letters. */
@Composable
fun HeroInfo(
    title: String,
    logoUrl: String?,
    description: String?,
    providerLabel: String?,
    modifier: Modifier = Modifier,
) {
    var logoFailed by remember(logoUrl) { mutableStateOf(logoUrl.isNullOrBlank()) }
    Column(modifier.width(560.dp)) {
        if (providerLabel != null) {
            Text(providerLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = ZapperColors.Accent)
        }
        if (!logoFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier.padding(vertical = 8.dp).heightIn(max = 110.dp).fillMaxWidth(0.8f),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = ZapperColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
