/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * Wraps [content] with the dim every selectable card in the app takes while [isSelectionMode] is
 * active and this card is not selected. The card itself carries the [SelectionMark] at the end of
 * its row, so the mark never sits over the text.
 */
@Composable
fun SelectableCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    content: @Composable () -> Unit
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (isSelectionMode && !isSelected) 0.55f else 1f,
        animationSpec = tween(200),
        label = "card_alpha"
    )

    Box(modifier = modifier.graphicsLayer { alpha = cardAlpha }) {
        content()
    }
}

/**
 * Mark at the end of a selectable card's row, popping in while the card can be picked: checked
 * when [selected], an empty circle when not, and gone when null outside a selection.
 *
 * @param ink See [SelectionCheckIndicator].
 * @param idle What the slot holds outside a selection, such as a chevron. The two swap in place,
 *   rather than one making room for the other.
 */
@Composable
fun RowScope.SelectionMark(
    selected: Boolean?,
    modifier: Modifier = Modifier,
    ink: Color? = null,
    idle: (@Composable () -> Unit)? = null
) {
    // Kept while the mark pops out, which happens after the selection has been cleared
    val shown = remember { mutableStateOf(false) }
    if (selected != null) shown.value = selected
    val mark = @Composable { SelectionCheckIndicator(ToggleableState(shown.value), ink = ink) }

    if (idle == null) {
        AnimatedVisibility(
            visible = selected != null,
            modifier = modifier,
            enter = Animations.headerActionEnter,
            exit = Animations.headerActionExit
        ) {
            mark()
        }
        return
    }

    SwappedSlot(showsMark = selected != null, mark = mark, idle = idle, modifier = modifier)
}

/** Size of the circle [SelectionCheckIndicator] draws, and of the slot a [SelectionMark] holds. */
internal val SelectionMarkSize = 28.dp

/** One place [mark] and [idle] pop in and out of in turn, without one moving the other aside. */
@Composable
private fun SwappedSlot(
    showsMark: Boolean,
    mark: @Composable () -> Unit,
    idle: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // Held at the mark's size throughout, so the slot does not snap narrower once the mark is gone
    Box(
        modifier = modifier.sizeIn(minWidth = SelectionMarkSize, minHeight = SelectionMarkSize),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = !showsMark,
            enter = Animations.popEnter,
            exit = Animations.popExit
        ) {
            idle()
        }
        AnimatedVisibility(
            visible = showsMark,
            enter = Animations.popEnter,
            exit = Animations.popExit
        ) {
            mark()
        }
    }
}
