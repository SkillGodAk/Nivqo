/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchBundleRepository.ImportMode
import app.morphe.manager.ui.screen.shared.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * Two-choice dialog that asks the user how an import should be applied: Merge only adds missing
 * entries and preserves everything that already exists, Replace overwrites current state to match
 * the backup exactly. The safe one leads, and the one that removes things says so with its icon.
 */
@Composable
fun DialogHostScope.ImportModeDialog(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    onDismiss: () -> Unit,
    onSelect: (ImportMode) -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(titleRes),
        description = stringResource(descriptionRes),
        footer = {
            // The rows are the choice, so the way out is kept outlined
            AppDialogOutlinedButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        SettingsGroup {
            SettingsItem(
                onClick = { onSelect(ImportMode.Merge) },
                title = stringResource(R.string.import_mode_merge_title),
                subtitle = stringResource(R.string.import_mode_merge_description),
                icon = MaterialSymbols.Rounded.Merge_type.autoMirrored
            )
            SettingsItem(
                onClick = { onSelect(ImportMode.Replace) },
                title = stringResource(R.string.import_mode_replace_title),
                subtitle = stringResource(R.string.import_mode_replace_description),
                leadingContent = {
                    ThemedIcon(icon = MaterialSymbols.Rounded.Swap_vert, tint = dialogDestructiveColor())
                }
            )
        }
    }
}
