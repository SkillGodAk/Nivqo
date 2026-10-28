/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import org.koin.compose.koinInject

/**
 * Reorder mode of a [MultiSelectBar]: whether it is on, and what its pills do. The mode is
 * entered through one of the bar's actions. A list with no order of its own to go back to leaves
 * [onReset] out.
 */
internal class MultiSelectReorder(
    val isActive: Boolean,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onReset: (() -> Unit)? = null
)

/**
 * Everything [MultiSelectBar] renders from state its own actions clear, kept together so it
 * can be frozen as a single value while the bar slides out. Anything that changes as a result
 * of using the bar belongs here rather than being read straight from a parameter.
 */
private data class MultiSelectDisplay(
    val count: Int,
    val total: Int,
    val reorder: MultiSelectReorder?,
    val actions: List<SelectionAction>
)

/**
 * Selection panel of a list whose items are long-pressed into a selection, which slides up while
 * they are being selected and turns into the reorder panel while [reorder] is on. Back leaves
 * whichever of the two is showing, the reorder without saving.
 */
@Composable
internal fun MultiSelectBar(
    selectedCount: Int,
    totalCount: Int,
    visible: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onCancel: () -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    reorder: MultiSelectReorder? = null
) {
    val messages = koinInject<UserMessages>()
    val reorderDone = stringResource(R.string.reorder_done)
    val resetOrderDone = stringResource(R.string.reset_order_done)

    val selection = rememberWhileVisible(
        visible,
        MultiSelectDisplay(
            count = selectedCount,
            total = totalCount,
            reorder = reorder,
            actions = actions
        )
    )

    DockedPanel(
        visible = visible,
        modifier = modifier,
        onBack = { if (reorder?.isActive == true) reorder.onCancel() else onCancel() }
    ) {
        AnimatedContent(
            targetState = selection.reorder?.isActive == true,
            transitionSpec = Animations.fadeCrossfade(200),
            label = "multibar_mode"
        ) { inReorder ->
            val activeReorder = selection.reorder
            if (inReorder && activeReorder != null) {
                ReorderPanel(
                    onDone = messages.confirming(reorderDone, activeReorder.onSave),
                    onReset = activeReorder.onReset?.let { messages.confirming(resetOrderDone, it) },
                    onCancel = activeReorder.onCancel
                )
            } else {
                SelectionActionBar(
                    selectedCount = selection.count,
                    totalCount = selection.total,
                    onSelectAll = onSelectAll,
                    onDeselectAll = onDeselectAll,
                    onCancel = onCancel,
                    actions = selection.actions
                )
            }
        }
    }
}

/**
 * Drag hint over the ways out of a reorder: [onDone] keeps the new order, [onReset] goes back to
 * the default one, and [onCancel] drops the changes. A reorder without the last two offers only
 * the first.
 */
@Composable
internal fun ReorderPanel(
    onDone: () -> Unit,
    onReset: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    val reorderListHint = stringResource(R.string.reorder_list_hint)
    val doneLabel = stringResource(R.string.done)
    val resetOrderLabel = stringResource(R.string.reset_order)
    val cancelLabel = stringResource(android.R.string.cancel)

    Column {
        PanelHeader(title = { PanelTitle(text = reorderListHint) }) {
            if (onCancel != null) {
                TitleAction(
                    icon = MaterialSymbols.Rounded.Close,
                    contentDescription = cancelLabel,
                    onClick = onCancel,
                    style = TitleActionStyle.Neutral
                )
            }
        }
        PanelActions(
            actions = listOfNotNull(
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Check,
                    label = doneLabel,
                    onClick = onDone,
                    tone = ActionTone.Primary
                ),
                onReset?.let {
                    SelectionAction(
                        icon = MaterialSymbols.Rounded.History,
                        label = resetOrderLabel,
                        onClick = it,
                        tone = ActionTone.Destructive
                    )
                }
            )
        )
    }
}

/**
 * The handle a list item is dragged into a new order by, to be given the reorderable library's
 * `draggableHandle` through [modifier] so its state stays with the list.
 */
@Composable
internal fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Drag_handle,
            contentDescription = stringResource(R.string.reorder_list),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
