/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/**
 * Names [content] in a tooltip on a long press or a hover, for a control that shows its icon
 * alone. A null [text] leaves the control bare, for one whose label is already in sight.
 *
 * [modifier] lands on the outermost node, where the surrounding layout reads it, as a row
 * does a weight. Semantics belong on the control in [content], the node a screen reader focuses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconTooltip(
    text: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // TooltipBox nests the modifier it is given inside wrappers of its own, out of reach of the
    // surrounding layout, so a Box that passes its constraints straight through carries it instead
    Box(modifier = modifier, propagateMinConstraints = true) {
        // Kept in place while the text comes and goes, so the control under it keeps its state
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(text.orEmpty()) } },
            state = rememberTooltipState(),
            enableUserInput = text != null,
            content = content
        )
    }
}

/**
 * Flat icon button named by [contentDescription], both to a screen reader and in an
 * [IconTooltip]. With no circle to round, it sinks under a press instead.
 */
@Composable
fun TooltipIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = Defaults.IconSize
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconTooltip(text = contentDescription) {
        IconButton(
            onClick = onClick,
            modifier = modifier.pressScale(
                interactionSource = interactionSource,
                enabled = enabled,
                label = "icon_button_press_scale"
            ),
            enabled = enabled,
            interactionSource = interactionSource
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = if (enabled) tint else tint.copy(alpha = tint.alpha * Defaults.DISABLED_ALPHA)
            )
        }
    }
}
