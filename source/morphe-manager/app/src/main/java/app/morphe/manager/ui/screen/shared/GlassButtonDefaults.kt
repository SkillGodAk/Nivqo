/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.morphe.manager.ui.theme.ThemeTraitsDefaults
import app.morphe.manager.ui.theme.isDarkTheme

/**
 * Shared color tokens for the frosted-glass button/row family used across the home
 * surface (pill buttons, category headers, segmented tabs) and the settings tab bar.
 * Retune once here to shift the palette everywhere consistently.
 */
object GlassButtonDefaults {
    /** Corner radius of the whole glass button family, kept in step with the dialog buttons. */
    val ButtonShape: Shape = RoundedCornerShape(Defaults.CardCornerRadius)

    val IconSize = Defaults.IconSize
    val HorizontalPadding = 12.dp
    val IconLabelSpacing = 8.dp

    /** Typography of the button label, shared so a measured fit matches what gets drawn. */
    val labelStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)

    @Composable
    fun containerColor(selected: Boolean = false): Color = containerColor(
        base = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        selected = selected
    )

    /** Glass fill tinted by [base], for accents outside the selected/unselected pair. */
    @Composable
    fun containerColor(base: Color, selected: Boolean): Color {
        val isDark = isDarkTheme()
        val alpha = if (selected) {
            if (isDark) 0.55f else 0.72f
        } else {
            if (isDark) 0.35f else 0.6f
        }
        return ThemeTraitsDefaults.surfaceColor(base = base.copy(alpha = alpha), selected = selected)
    }

    @Composable
    fun contentColor(selected: Boolean = false): Color =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    /** Content color paired with a tinted [containerColor]. */
    @Composable
    fun contentColor(base: Color, selected: Boolean): Color =
        ThemeTraitsDefaults.contentColor(base = base, neutral = contentColor(selected))

    @Composable
    fun borderColor(selected: Boolean = false): Color =
        borderColor(base = MaterialTheme.colorScheme.primary, selected = selected)

    /** Border paired with a tinted [containerColor]; [base] only carries the selected state. */
    @Composable
    fun borderColor(base: Color, selected: Boolean): Color {
        val isDark = isDarkTheme()
        val edge = if (selected) {
            base.copy(alpha = if (isDark) 0.55f else 0.45f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.4f else 0.6f)
        }
        return ThemeTraitsDefaults.borderColor(base = edge, selected = selected)
    }
}
