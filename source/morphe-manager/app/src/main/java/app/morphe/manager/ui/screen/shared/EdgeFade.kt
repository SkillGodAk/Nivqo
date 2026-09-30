/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How far into a row its edge fades, enough to read as more to come without hiding a label. */
val EdgeFadeWidth = 24.dp

/**
 * Clips to bounds and fades the start and end edges out to nothing, each over as many pixels as
 * it hides, up to [length]. An edge fades only while something is cut off behind it, and a sliver
 * cut off still fades softly rather than ending in a hard line.
 *
 * Fades the content itself rather than laying a strip of one color over it, so it works on any
 * surface, tinted ones included, which no single gradient color could match.
 *
 * @param length The furthest in from an edge its fade reaches.
 * @param orientation Which edges fade: start and end of a row, or top and bottom of a column.
 * @param startInset How far in from the start edge its fade begins, in pixels, leaving what lies
 *   before it as drawn. For content pinned over the start, which stays whole while the rest
 *   fades out under it.
 */
fun Modifier.edgeFade(
    length: Dp,
    hiddenAtStart: () -> Float,
    hiddenAtEnd: () -> Float,
    orientation: Orientation = Orientation.Horizontal,
    startInset: () -> Float = { 0f }
): Modifier = clipToBounds()
    .graphicsLayer {
        // The fade masks what is already drawn, which takes a layer of its own
        compositingStrategy = if (hiddenAtStart() > 0f || hiddenAtEnd() > 0f) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    .drawWithContent {
        drawContent()
        val inset = startInset().coerceAtLeast(0f)
        val extent = (if (orientation == Orientation.Horizontal) size.width else size.height) - inset
        val maxLength = length.toPx().coerceAtMost(extent / 2)
        if (maxLength <= 0f) return@drawWithContent
        // A row starts on the right in a right-to-left layout, a column always at the top
        val startIsFirst = orientation == Orientation.Vertical || layoutDirection == LayoutDirection.Ltr
        fadeEdge(orientation, atFirst = startIsFirst, length = hiddenAtStart().coerceAtMost(maxLength), inset = inset)
        fadeEdge(orientation, atFirst = !startIsFirst, length = hiddenAtEnd().coerceAtMost(maxLength))
    }

/** [edgeFade] for a row that scrolls sideways, fading whichever end [scrollState] has more past. */
fun Modifier.horizontalScrollFade(scrollState: ScrollState, length: Dp = EdgeFadeWidth): Modifier =
    scrollFade(scrollState, length, Orientation.Horizontal)

/** [edgeFade] for a column that scrolls, fading whichever end [scrollState] has more past. */
fun Modifier.verticalScrollFade(scrollState: ScrollState, length: Dp = EdgeFadeWidth): Modifier =
    scrollFade(scrollState, length, Orientation.Vertical)

/**
 * [edgeFade] for a lazy column, fading whichever end [state] has more past.
 *
 * @param pinnedFirstRow Whether the first row sticks to the top, a search field say. The top fade
 *   then starts under it, so the rows fade out as they scroll beneath it and the row stays whole.
 */
fun Modifier.verticalScrollFade(
    state: LazyListState,
    length: Dp = EdgeFadeWidth,
    pinnedFirstRow: Boolean = false
): Modifier = edgeFade(
    length = length,
    hiddenAtStart = { state.hiddenAtStart() },
    hiddenAtEnd = { state.hiddenAtEnd() },
    orientation = Orientation.Vertical,
    startInset = { if (pinnedFirstRow) state.firstRowEnd() else 0f }
)

// A lazy list measures only the rows in view, so a row out of sight counts as more than any fade
private fun LazyListState.hiddenAtStart(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()

// Where the first row ends, measured from the top of the list, or 0 once it is out of view
private fun LazyListState.firstRowEnd(): Float {
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return 0f
    return (first.offset + first.size - info.viewportStartOffset).coerceAtLeast(0).toFloat()
}

private fun LazyListState.hiddenAtEnd(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset)
        .coerceAtLeast(0)
        .toFloat()
}

/** [edgeFade] for a lazy grid, fading whichever end [state] has more past. */
fun Modifier.verticalScrollFade(state: LazyGridState, length: Dp = EdgeFadeWidth): Modifier = edgeFade(
    length = length,
    hiddenAtStart = { state.hiddenAtStart() },
    hiddenAtEnd = { state.hiddenAtEnd() },
    orientation = Orientation.Vertical
)

// The first visible item always opens a line, so past index 0 the whole first line is out of view
private fun LazyGridState.hiddenAtStart(): Float =
    if (firstVisibleItemIndex > 0) Float.MAX_VALUE else firstVisibleItemScrollOffset.toFloat()

// Cells of the last line may differ in height, so the lowest edge among them is what counts
private fun LazyGridState.hiddenAtEnd(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
    val bottom = info.visibleItemsInfo.maxOf { it.offset.y + it.size.height }
    return (bottom + info.afterContentPadding - info.viewportEndOffset)
        .coerceAtLeast(0)
        .toFloat()
}

private fun Modifier.scrollFade(scrollState: ScrollState, length: Dp, orientation: Orientation) = edgeFade(
    length = length,
    hiddenAtStart = { scrollState.value.toFloat() },
    hiddenAtEnd = { (scrollState.maxValue - scrollState.value).toFloat() },
    orientation = orientation
)

// Masks the fade draws with, kept rather than built on every frame an edge fades
private val FadeInMask = listOf(Color.Transparent, Color.Black)
private val FadeOutMask = listOf(Color.Black, Color.Transparent)

/**
 * Fades one edge out to nothing over [length]: the left or top one when [atFirst], the right or
 * bottom one otherwise, starting [inset] in from it.
 */
private fun DrawScope.fadeEdge(
    orientation: Orientation,
    atFirst: Boolean,
    length: Float,
    inset: Float = 0f
) {
    if (length <= 0f) return
    val colors = if (atFirst) FadeInMask else FadeOutMask

    if (orientation == Orientation.Horizontal) {
        val left = if (atFirst) inset else size.width - length - inset
        drawRect(
            brush = Brush.horizontalGradient(colors, startX = left, endX = left + length),
            topLeft = Offset(left, 0f),
            size = Size(length, size.height),
            blendMode = BlendMode.DstIn
        )
    } else {
        val top = if (atFirst) inset else size.height - length - inset
        drawRect(
            brush = Brush.verticalGradient(colors, startY = top, endY = top + length),
            topLeft = Offset(0f, top),
            size = Size(size.width, length),
            blendMode = BlendMode.DstIn
        )
    }
}
