/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import org.koin.compose.koinInject

/**
 * A list being dragged into a new order, and the ways out of it: [onDone] keeps the new order,
 * [onReset] goes back to the default one, and [onCancel] drops the changes. A list with no order
 * of its own to go back to leaves [onReset] out, and one with nothing to drop [onCancel].
 */
@Immutable
internal class ListReorder(
    val onDone: () -> Unit,
    val onReset: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null
)

/**
 * The page header's controls while [reorder] is under way, in place of its own, so the whole page
 * is left to the list being dragged. Back leaves the reorder, dropping the changes where it can.
 */
@Composable
internal fun ReorderHeaderActions(reorder: ListReorder) {
    val messages = koinInject<UserMessages>()
    val onDone = messages.confirming(stringResource(R.string.reorder_done), reorder.onDone)
    val onReset = reorder.onReset?.let { messages.confirming(stringResource(R.string.reset_order_done), it) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = reorder.onCancel ?: onDone)

    // An order dragged by hand is lost to a stray tap, so going back to the default one is asked first
    DialogHost(visible = confirmReset && onReset != null) {
        ConfirmDialog(
            title = stringResource(R.string.reset_order_confirm_title),
            message = stringResource(R.string.reset_order_confirm_message),
            primaryText = stringResource(R.string.reset_order),
            onConfirm = {
                confirmReset = false
                onReset?.invoke()
            },
            onDismiss = { confirmReset = false }
        )
    }

    if (onReset != null) {
        TitleAction(
            icon = MaterialSymbols.Rounded.History,
            contentDescription = stringResource(R.string.reset_order),
            onClick = { confirmReset = true },
            style = TitleActionStyle.Destructive
        )
    }
    reorder.onCancel?.let { onCancel ->
        TitleAction(
            icon = MaterialSymbols.Rounded.Close,
            contentDescription = stringResource(android.R.string.cancel),
            onClick = onCancel,
            style = TitleActionStyle.Neutral
        )
    }
    TitleAction(
        icon = MaterialSymbols.Rounded.Check,
        contentDescription = stringResource(R.string.done),
        onClick = onDone,
        style = TitleActionStyle.Accent
    )
}
