/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import app.morphe.manager.util.ensureContrast

/**
 * What the card in this subtree is filled with.
 *
 * Cards colored from an app's own icon can land on any hue, so a fill taken from the palette has
 * no way of staying clear of them on its own.
 */
val LocalCardBackground = compositionLocalOf<Color?> { null }

/** How far a fill is pushed off a card it would otherwise match. */
private const val CardSeparation = 0.15f

/** This color, moved off [LocalCardBackground] when the two are too close to tell apart. */
@Composable
fun Color.distinctFromCard(): Color {
    val card = LocalCardBackground.current ?: return this
    return ensureContrast(card, CardSeparation)
}
