/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A dialog's lazy list, fading at whichever end has more to show, with a [ListScrollbar] and a
 * [ScrollToTopButton] set out at the dialog's true edge rather than its content inset.
 *
 * @param pinnedFirstRow Whether the first row sticks to the top, see [verticalScrollFade].
 */
@Composable
fun DialogLazyList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    pinnedFirstRow: Boolean = false,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val edgeOffset = Modifier.offset(x = LocalDialogHorizontalInset.current)

    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollFade(state, pinnedFirstRow = pinnedFirstRow),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content
        )

        ListScrollbar(listState = state, modifier = edgeOffset)
        ScrollToTopButton(listState = state, modifier = edgeOffset)
    }
}

/**
 * [DialogLazyList] for content laid out in full rather than row by row, such as a few sections of
 * details that are never long enough to need a lazy list.
 */
@Composable
fun DialogScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    val edgeOffset = Modifier.offset(x = LocalDialogHorizontalInset.current)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollFade(state)
                .verticalScroll(state)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )

        ListScrollbar(scrollState = state, modifier = edgeOffset)
        ScrollToTopButton(scrollState = state, modifier = edgeOffset)
    }
}
