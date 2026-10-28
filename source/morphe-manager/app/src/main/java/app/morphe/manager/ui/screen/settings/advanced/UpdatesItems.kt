/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * Rows of a settings group for updates of the manager and its sources: pre-releases, metered
 * connections and, in expert mode, the GitHub token the update checks are made with.
 */
@Composable
fun UpdatesItems(
    settingsViewModel: SettingsViewModel,
    onManagerPrereleasesToggle: () -> Unit
) {
    val prefs = settingsViewModel.prefs
    val backgroundUpdateNotifications by prefs.backgroundUpdateNotifications.getAsState()
    val allowMeteredUpdates by prefs.allowMeteredUpdates.getAsState()
    val useManagerPrereleases by prefs.useManagerPrereleases.getAsState()
    val usePatchesPrereleases by prefs.bundlePrereleasesEnabled.getAsState()
    val useExpertMode by prefs.useExpertMode.getAsState()
    val showPrereleaseWarning = remember { mutableStateOf(false) }

    fun applyManagerPrereleases() {
        settingsViewModel.toggleManagerPrereleases(
            currentValue = useManagerPrereleases,
            backgroundNotificationsEnabled = backgroundUpdateNotifications,
            patchesPrereleaseIds = usePatchesPrereleases,
            onCheckUpdate = onManagerPrereleasesToggle
        )
    }

    DialogHost(visible = showPrereleaseWarning.value) {
        ConfirmDialog(
            title = stringResource(R.string.settings_advanced_updates_prerelease_warning_title),
            message = stringResource(R.string.settings_advanced_updates_prerelease_warning_message),
            primaryText = stringResource(R.string.enable),
            isPrimaryDestructive = false,
            onDismiss = { showPrereleaseWarning.value = false },
            onConfirm = {
                showPrereleaseWarning.value = false
                applyManagerPrereleases()
            }
        )
    }

    // Use manager prereleases toggle
    SettingsSwitchItem(
        checked = useManagerPrereleases,
        onToggle = {
            if (useManagerPrereleases) {
                applyManagerPrereleases()
            } else {
                // Explain what pre-release means, and that patches are separate, before flipping it on
                showPrereleaseWarning.value = true
            }
        },
        icon = MaterialSymbols.Rounded.Science,
        title = stringResource(R.string.settings_advanced_updates_manager_prereleases),
        subtitle = stringResource(R.string.settings_advanced_updates_manager_prereleases_description)
    )

    // Allow updates on metered connections
    SettingsSwitchItem(
        checked = allowMeteredUpdates,
        onToggle = { settingsViewModel.toggleAllowMeteredUpdates(allowMeteredUpdates) },
        icon = MaterialSymbols.Rounded.Signal_cellular_alt,
        title = stringResource(R.string.settings_advanced_updates_allow_metered),
        subtitle = stringResource(R.string.settings_advanced_updates_allow_metered_description)
    )

    if (useExpertMode) {
        GitHubPatSettingsItem(settingsViewModel)
    }
}
