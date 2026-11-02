/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import app.morphe.manager.R

/**
 * The Morphe launcher logo in its own colors, for the white circle Morphe itself is shown in: the
 * source it ships with, and its own update. Scaled up past the foreground's safe zone to fill it.
 */
@Composable
fun MorpheLauncherLogo(modifier: Modifier = Modifier) {
    Image(
        painter = rememberAppCompatPainter(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            scaleX = 1.5f
            scaleY = 1.5f
        }
    )
}

/**
 * Nivqo wordmark: use our established N launcher mark plus the remaining letters.
 * The full Morphe upstream wordmark is intentionally not used in Nivqo's home header.
 */
@Composable
fun NivqoWordmark(
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The launcher vector has Android's adaptive-icon safe-zone padding. Crop that
        // transparent padding so the mark reads as a properly sized wordmark letter.
        Box(modifier = Modifier.size(26.dp).clipToBounds()) {
            Image(
                painter = rememberAppCompatPainter(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 2.1f
                    scaleY = 2.1f
                }
            )
        }
        Text(
            text = "ivqo",
            color = textColor,
            fontSize = 25.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Painter for a vector of the Morphe branding, loaded through AppCompat as `painterResource` cannot
 * be trusted with them in release builds, see [rememberMorpheLogoBitmap].
 */
@Composable
private fun rememberAppCompatPainter(@DrawableRes id: Int): Painter {
    val context = LocalContext.current
    return rememberDrawablePainter(drawable = remember(context, id) { AppCompatResources.getDrawable(context, id) })
}

/**
 * Monochrome Morphe logo rasterized for use with a tinted [androidx.compose.material3.Icon].
 * Loaded through AppCompat and drawn to a bitmap because `painterResource` crashes on this vector in release builds.
 */
@Composable
fun rememberMorpheLogoBitmap(): ImageBitmap? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_mpp) ?: return@runCatching null
            val size = 96
            // The path spans nearly the full viewport, so inset it to Material's 20dp live area
            val inset = size / 24
            val bmp = createBitmap(size, size)
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(Canvas(bmp))
            bmp.asImageBitmap()
        }.getOrNull()
    }
}
