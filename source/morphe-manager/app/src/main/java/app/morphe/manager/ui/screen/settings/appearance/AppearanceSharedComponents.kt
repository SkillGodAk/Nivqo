/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*

/**
 * Lays [items] out [columns] to a row, padding a short last row so every tile keeps one width.
 */
@Composable
fun <T> OptionGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    item: @Composable (item: T, modifier: Modifier) -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                row.forEach { item(it, Modifier.weight(1f)) }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Standard icon-based option card for appearance settings.
 * Used for backgrounds, themes, and other icon-based selections.
 *
 * @param compact Shorter, with a smaller icon, for a row of choices that sits inline on the tab
 *        rather than in a picker of its own.
 */
@Composable
fun ModernIconOptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val windowSize = rememberWindowSize()
    val iconSize = when {
        compact -> 22.dp
        windowSize.widthSizeClass == WindowWidthSizeClass.Compact -> 32.dp
        windowSize.widthSizeClass == WindowWidthSizeClass.Medium -> 36.dp
        else -> 40.dp
    }

    // Increase height in landscape to prevent text clipping
    val cardHeight = when {
        compact -> if (isLandscape()) 72.dp else 64.dp
        else -> if (isLandscape()) 92.dp else 80.dp
    }

    SelectionTile(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        stateDescription = stringResource(
            if (selected) R.string.selected else R.string.not_selected
        ),
        modifier = modifier.height(cardHeight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Defaults.ItemSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // Selected tiles carry what the tile resolved against its own fill, the rest keep
                // the accent they are drawn in
                tint = if (selected) {
                    LocalContentColor.current
                } else {
                    MaterialTheme.colorScheme.primary
                }.copy(alpha = if (enabled) 1f else 0.5f),
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    LocalContentColor.current
                } else {
                    MaterialTheme.colorScheme.onSurface
                }.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 2, // Allow 2 lines for text wrapping
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.2
            )
        }
    }
}
