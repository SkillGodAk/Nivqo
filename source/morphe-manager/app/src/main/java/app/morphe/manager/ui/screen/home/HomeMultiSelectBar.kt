/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.animation.AnimatedContent
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
    val inReorderMode: Boolean,
    val showEditActions: Boolean
)

/**
 * Slide-up panel for the long-pressed category or source header, which turns into the reorder
 * panel while the headers are being dragged into a new order. A source group only offers
 * reordering: its name and its apps come from the source, not from the user.
 *
 * @param onBack Closes the panel from either mode, dropping a reorder that was not saved.
 */
@Composable
internal fun CategoryActionBar(
    activeCategoryTitle: String?,
    visible: Boolean,
    isReorderMode: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEnterReorder: () -> Unit,
    onExitReorder: () -> Unit,
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
        CategoryDisplay(
            title = activeCategoryTitle,
            inReorderMode = isReorderMode,
            showEditActions = showEditActions
        )
    )

    DockedPanel(visible = visible, modifier = modifier, onBack = onBack) {
        AnimatedContent(
            targetState = category.inReorderMode,
            transitionSpec = Animations.fadeCrossfade(200),
            label = "category_bar_mode"
        ) { inReorder ->
            if (inReorder) {
                ReorderPanel(onDone = onExitReorder)
            } else {
                Column {
                    PanelHeader(title = { category.title?.let { PanelTitle(text = it) } }) {
                        TitleAction(
                            icon = MaterialSymbols.Rounded.Close,
                            contentDescription = cancelLabel,
                            onClick = onCancel,
                            style = TitleActionStyle.Neutral
                        )
                    }
                    PanelActions(
                        actions = buildList {
                            if (category.showEditActions) {
                                add(SelectionAction(MaterialSymbols.Rounded.Edit, renameLabel, onRename))
                            }
                            add(SelectionAction(MaterialSymbols.Rounded.Reorder, reorderListLabel, onEnterReorder))
                            if (category.showEditActions) {
                                add(
                                    SelectionAction(
                                        icon = MaterialSymbols.Rounded.Delete,
                                        label = deleteLabel,
                                        onClick = onDelete,
                                        tone = ActionTone.Destructive
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
