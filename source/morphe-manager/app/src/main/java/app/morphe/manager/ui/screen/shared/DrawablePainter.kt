/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * Painter for a [Drawable] that Compose cannot load as a resource, such as an adaptive icon or a
 * layer list. The drawables shown this way are static, so it draws them without animation support.
 */
@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter =
    remember(drawable) { drawable?.let(::DrawablePainter) ?: ColorPainter(Color.Transparent) }

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth >= 0 && drawable.intrinsicHeight >= 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
