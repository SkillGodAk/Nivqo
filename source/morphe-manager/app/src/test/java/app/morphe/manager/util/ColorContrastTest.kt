/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure black, which every tinted fill on the home screen is drawn over in that theme. */
private val PureBlack = Color.Black

/** The bar [readableOn] holds content to, WCAG AA for large text. */
private const val MinRatio = 3f

/**
 * What keeps a label legible once its fill is drawn translucent. The palette's own pairing stops
 * describing the background at that point, and a wallpaper palette is free to pair colors that
 * only work opaque.
 */
class ColorContrastTest {
    @Test
    fun `black on white is the widest contrast there is`() {
        assertEquals(21f, Color.Black.contrastAgainst(Color.White), absoluteTolerance = 0.01f)
        assertEquals(1f, Color.White.contrastAgainst(Color.White), absoluteTolerance = 0.01f)
    }

    @Test
    fun `a label that cannot be read on its own fill is replaced`() {
        // Wallpaper palette whose tertiary container is light, so half-transparency lands it
        // mid-tone and its on-color, meant for the opaque fill, no longer carries
        val fill = Color(0xFFC8CFFD).copy(alpha = 0.55f)
        val label = Color(0xFFADAFC8)

        val readable = label.readableOn(fill, PureBlack)

        assertEquals(Color.White, readable)
        assertTrue(label.contrastAgainst(fill.compositeOver(PureBlack)) < MinRatio)
        assertTrue(readable.contrastAgainst(fill.compositeOver(PureBlack)) >= MinRatio)
    }

    @Test
    fun `a label that already reads is left untouched`() {
        // Both taken from the bundled dark theme, where containers are darker than their on-colors
        // and transparency only widens the gap
        val selected = Color(0xFF004884).copy(alpha = 0.55f) to Color(0xFFD4E3FF)
        val recommended = Color(0xFF543F5E).copy(alpha = 0.6f) to Color(0xFFF6D9FF)

        listOf(selected, recommended).forEach { (fill, label) ->
            assertEquals(label, label.readableOn(fill, PureBlack))
        }
    }

    @Test
    fun `a label is replaced when the fill it lands on flips polarity`() {
        // A light accent container pairs with a dark on-color, and tinting takes the fill dark
        // underneath it, which contrast alone lets through
        val fill = Color(0xFFFEE27F).copy(alpha = 0.55f)
        val label = Color.Black
        val drawn = fill.compositeOver(PureBlack)

        assertTrue(label.contrastAgainst(drawn) >= MinRatio)
        assertEquals(Color.White, label.readableOn(fill, PureBlack))
    }

    @Test
    fun `a flipped label is replaced even where it also reads worse`() {
        // Same shape in orange, where the palette's own pick is the weaker of the two as well
        val fill = Color(0xFFFFAF70).copy(alpha = 0.55f)
        val drawn = fill.compositeOver(PureBlack)

        val replaced = Color.Black.readableOn(fill, PureBlack)

        assertEquals(Color.White, replaced)
        assertTrue(replaced.contrastAgainst(drawn) > Color.Black.contrastAgainst(drawn))
    }

    @Test
    fun `a dimmed label stays dimmed after being replaced`() {
        val fill = Color(0xFFC8CFFD).copy(alpha = 0.55f)
        val label = Color(0xFFADAFC8).copy(alpha = 0.5f)

        assertEquals(0.5f, label.readableOn(fill, PureBlack).alpha, absoluteTolerance = 0.01f)
    }
}
