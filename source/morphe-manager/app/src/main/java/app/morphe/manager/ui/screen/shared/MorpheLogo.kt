/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import app.morphe.manager.R

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
