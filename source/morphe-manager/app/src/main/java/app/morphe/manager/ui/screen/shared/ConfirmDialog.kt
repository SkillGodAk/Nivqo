/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/** One thing a confirmed action removes or resets, with its size or amount as [detail]. */
@Immutable
data class ConfirmItem(
    val icon: ImageVector,
    val text: String,
    val detail: String? = null
)

/**
 * Asks to confirm an action, the one dialog every delete, clear and reset goes through so they
 * all read alike.
 *
 * @param message What the action does: plain text, or an [AnnotatedString] for one with emphasis.
 * @param subject The one thing the action is about, see [ConfirmSubject].
 * @param accentColor Color of that thing, which the dialog then wears as its other dialogs do.
 * @param items What the action removes, in a card headed by [itemsTitle], each with its size.
 * @param notice What the action leaves in place or makes harder later.
 */
@Composable
fun DialogHostScope.ConfirmDialog(
    title: String,
    primaryText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: CharSequence? = null,
    isPrimaryDestructive: Boolean = true,
    secondaryText: String = stringResource(android.R.string.cancel),
    subject: (@Composable () -> Unit)? = null,
    accentColor: Color? = null,
    items: List<ConfirmItem> = emptyList(),
    itemsTitle: String? = null,
    notice: String? = null
) {
    val hasContent = subject != null || items.isNotEmpty() || notice != null
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        description = message,
        accentColor = accentColor,
        footer = {
            AppDialogButtonRow(
                primaryText = primaryText,
                onPrimaryClick = onConfirm,
                isPrimaryDestructive = isPrimaryDestructive,
                secondaryText = secondaryText,
                onSecondaryClick = onDismiss
            )
        }
    ) {
        if (hasContent) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
            ) {
                subject?.invoke()
                if (items.isNotEmpty()) {
                    LabeledSection(
                        title = itemsTitle,
                        icon = MaterialSymbols.Rounded.Delete.takeIf { itemsTitle != null }
                    ) {
                        items.forEach { DeleteListItem(icon = it.icon, text = it.text, detail = it.detail) }
                    }
                }
                if (notice != null) {
                    Footnote(
                        text = notice,
                        tone = SemanticTone.Warning,
                        icon = MaterialSymbols.Rounded.Info
                    )
                }
            }
        }
    }
}

/** Head of a [ConfirmDialog] about one thing: its [icon] over its [name]. */
@Composable
fun ConfirmSubject(
    name: String,
    icon: @Composable (Modifier) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
    ) {
        icon(Modifier.size(64.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = dialogTextColor(),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Asks before a download over a metered connection, where the provider may charge for the data.
 */
@Composable
fun DialogHostScope.MeteredDownloadDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.download),
                onPrimaryClick = onConfirm,
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Notice(
            icon = MaterialSymbols.Rounded.Warning,
            text = stringResource(R.string.download_confirmation_metered),
            tone = SemanticTone.Warning
        )
    }
}
