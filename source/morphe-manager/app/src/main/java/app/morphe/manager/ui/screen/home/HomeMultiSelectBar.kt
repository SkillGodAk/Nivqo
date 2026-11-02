/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/** What [CategoryActionBar] renders, frozen as one value while the bar slides out. */
private data class CategoryDisplay(
    val title: String?,
    val showEditActions: Boolean
)

/**
 * Slide-up panel for the long-pressed category or source header. A source group only offers
 * reordering: its name and its apps come from the source, not from the user.
 *
 * @param onEnterReorder Starts dragging the headers into a new order, whose controls the page header
 *   then takes, see [ReorderHeaderActions].
 */
@Composable
internal fun CategoryActionBar(
    activeCategoryTitle: String?,
    visible: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEnterReorder: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showEditActions: Boolean = true
) {
    val cancelLabel = stringResource(android.R.string.cancel)
    val renameLabel = stringResource(R.string.rename)
    val reorderListLabel = stringResource(R.string.reorder_list)
    val deleteLabel = stringResource(R.string.delete)

    val category = rememberWhileVisible(
        visible,
        CategoryDisplay(title = activeCategoryTitle, showEditActions = showEditActions)
    )

    DockedPanel(visible = visible, modifier = modifier, onBack = onBack) {
        Column {
            // Reordering acts on every header rather than on the one pressed, so it sits with the
            // panel's own controls, as in a selection panel
            PanelHeader(title = { category.title?.let { PanelTitle(text = it) } }) {
                TitleAction(
                    icon = MaterialSymbols.Rounded.Reorder,
                    contentDescription = reorderListLabel,
                    onClick = onEnterReorder,
                    style = TitleActionStyle.Neutral
                )
                TitleAction(
                    icon = MaterialSymbols.Rounded.Close,
                    contentDescription = cancelLabel,
                    onClick = onCancel,
                    style = TitleActionStyle.Neutral
                )
            }
            if (category.showEditActions) {
                PanelActions(
                    actions = listOf(
                        SelectionAction(MaterialSymbols.Rounded.Edit, renameLabel, onRename),
                        SelectionAction(
                            icon = MaterialSymbols.Rounded.Delete,
                            label = deleteLabel,
                            onClick = onDelete,
                            tone = ActionTone.Destructive
                        )
                    )
                )
            }
        }
    }
}
