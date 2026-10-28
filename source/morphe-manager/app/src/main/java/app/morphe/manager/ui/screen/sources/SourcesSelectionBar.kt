/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.bundles.PatchBundleSource
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.morphe.manager.domain.bundles.RemotePatchBundle
import app.morphe.manager.ui.screen.shared.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * The panel a selection of sources docks under the page, as the home screen's does for apps: what
 * can be done to every source picked at once, and the reorder it leads to.
 *
 * Each action reaches only the picked sources it applies to, so a selection mixing them still
 * offers it: a blocked source sits out updating and switching, and the default one deleting.
 *
 * @param sources Every source, which the picked uids are looked up in.
 * @param listed The sources on screen, which "select all" picks.
 * @param blocked Uids of the sources the blocklist names.
 * @param onStartReorder Leaves the selection for the reorder, or null while the list's order is not
 *   the user's own to drag.
 */
@Composable
internal fun SourcesSelectionBar(
    state: SourcesPageState,
    sources: List<PatchBundleSource>,
    listed: List<PatchBundleSource>,
    blocked: Set<Int>,
    onUpdate: (List<PatchBundleSource>) -> Unit,
    onToggleEnabled: (List<PatchBundleSource>) -> Unit,
    onDelete: (List<PatchBundleSource>) -> Unit,
    onStartReorder: (() -> Unit)?,
    onSaveOrder: () -> Unit,
    onCancelReorder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selection = state.selection
    val selected = sources.filter { selection.contains(it.uid) }
    val switchable = selected.filter { it.uid !in blocked }
    val updatable = switchable.filterIsInstance<RemotePatchBundle>()
    // A selection holding any source that is on switches them off, so one tap never flips the rest on
    val toDisable = switchable.filter { it.enabled }
    val deletable = selected.filterNot { it.isDefault }

    val updateLabel = stringResource(R.string.update)
    val disableLabel = stringResource(R.string.disable)
    val enableLabel = stringResource(R.string.enable)
    val reorderListLabel = stringResource(R.string.reorder_list)
    val deleteLabel = stringResource(R.string.delete)

    // Every action but deleting is done at once, which the selection then gives way to
    fun acting(action: () -> Unit): () -> Unit = {
        action()
        state.exitSelection()
    }

    val actions = buildList {
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Refresh,
                label = updateLabel,
                onClick = acting { onUpdate(updatable) },
                enabled = updatable.isNotEmpty(),
                tone = ActionTone.Primary
            )
        )
        add(
            if (toDisable.isNotEmpty()) {
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Block,
                    label = disableLabel,
                    onClick = acting { onToggleEnabled(toDisable) }
                )
            } else {
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Check_circle,
                    label = enableLabel,
                    onClick = acting { onToggleEnabled(switchable) },
                    enabled = switchable.isNotEmpty()
                )
            }
        )
        if (onStartReorder != null) {
            add(SelectionAction(MaterialSymbols.Rounded.Reorder, reorderListLabel, onStartReorder))
        }
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Delete,
                label = deleteLabel,
                onClick = { onDelete(deletable) },
                enabled = deletable.isNotEmpty(),
                tone = ActionTone.Destructive
            )
        )
    }

    MultiSelectBar(
        selectedCount = selection.size,
        totalCount = listed.size,
        visible = state.isSelecting || state.isReordering,
        onSelectAll = { selection.setAll(listed.map { it.uid }) },
        onDeselectAll = selection::clear,
        onCancel = state::exitSelection,
        actions = actions,
        reorder = MultiSelectReorder(
            isActive = state.isReordering,
            onSave = {
                onSaveOrder()
                state.exitReorder()
            },
            onCancel = {
                onCancelReorder()
                state.exitReorder()
            }
        ),
        modifier = modifier
    )
}
