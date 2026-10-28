/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import androidx.compose.runtime.*
import app.morphe.manager.ui.screen.shared.SelectionState
import app.morphe.manager.ui.screen.shared.rememberSelectionState

/**
 * What the sources page docks over its lower edge: the options the list is arranged by, or a
 * selection of sources and the reorder it leads to. Held by the screen, whose navigation gives way
 * to whichever is up. Only one ever is, as entering one leaves the others.
 */
@Stable
class SourcesPageState internal constructor(
    /** The sources picked, by uid. */
    val selection: SelectionState<Int>
) {
    var isListOptionsOpen by mutableStateOf(false)

    var isSelecting by mutableStateOf(false)
        private set

    var isReordering by mutableStateOf(false)
        private set

    /** True while a panel owns the page's lower edge, which the navigation then gives way to. */
    val isFooterBarVisible: Boolean
        get() = isListOptionsOpen || isSelecting || isReordering

    /** Opens or closes the options the list is arranged by, which a selection gives way to. */
    fun toggleListOptions() {
        if (!isListOptionsOpen) exitSelection()
        isListOptionsOpen = !isListOptionsOpen
    }

    /** Starts a selection on the source [uid] was long-pressed on. */
    fun startSelection(uid: Int) {
        isListOptionsOpen = false
        isSelecting = true
        selection.setAll(listOf(uid))
    }

    fun exitSelection() {
        isSelecting = false
        selection.clear()
    }

    /** Leaves the selection for dragging the sources into a new order, which picks none. */
    fun startReorder() {
        exitSelection()
        isReordering = true
    }

    fun exitReorder() {
        isReordering = false
    }
}

@Composable
fun rememberSourcesPageState(): SourcesPageState {
    val selection = rememberSelectionState<Int>()
    return remember(selection) { SourcesPageState(selection) }
}
