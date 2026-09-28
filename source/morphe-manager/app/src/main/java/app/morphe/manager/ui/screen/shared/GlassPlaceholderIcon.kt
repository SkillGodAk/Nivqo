package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.manager.util.endEdgeX
import app.morphe.manager.util.isRtl
import app.morphe.manager.util.startEdgeX

/**
 * Glass placeholder icon for apps that have not been patched yet.
 *
 * No inner padding is applied by default - pass [innerPadding] explicitly when you need
 * the placeholder to optically align with adaptive icons (which have ~10% inset).
 * Corner radius scales automatically with the drawn size.
 */
@Composable
fun GlassPlaceholderIcon(
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    innerPadding: Dp = 0.dp
) {
    val baseColor = gradientColors.firstOrNull() ?: Color.White
    val midColor = gradientColors.getOrElse(1) { baseColor }
    val endColor = gradientColors.lastOrNull() ?: baseColor
    val rtl = isRtl()

    Box(
        modifier = modifier
            .padding(innerPadding)
            // Brushes are rebuilt only when the size or the palette changes, so a list full of
            // placeholders does not reallocate them on every frame
            .drawWithCache {
                // Corner radius = ~20% of the shorter side, matching adaptive icon rounding
                val cr = CornerRadius(minOf(size.width, size.height) * 0.20f)
                val w = size.width
                val h = size.height
                val startX = startEdgeX(w, rtl)
                val endX = endEdgeX(w, rtl)

                // One sweep from the frosted top-start highlight into the tinted bottom-end. Every
                // translucent layer is another blend pass, paid once per placeholder on screen
                val glass = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.50f),
                        baseColor.copy(alpha = 0.22f),
                        endColor.copy(alpha = 0.20f)
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(endX, h)
                )

                // Border
                val border = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        midColor.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.35f)
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(endX, h)
                )
                val borderStroke = Stroke(width = 1.dp.toPx())

                onDrawBehind {
                    drawRoundRect(brush = glass, cornerRadius = cr)
                    drawRoundRect(brush = border, cornerRadius = cr, style = borderStroke)
                }
            }
    )
}
