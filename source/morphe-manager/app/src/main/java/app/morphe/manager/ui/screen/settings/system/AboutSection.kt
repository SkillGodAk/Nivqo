/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.morphe.manager.BuildConfig
import app.morphe.manager.R
import app.morphe.manager.data.platform.NetworkInfo
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.SettingsDivider
import app.morphe.manager.ui.screen.shared.SettingsGroup
import app.morphe.manager.ui.screen.shared.SettingsItem
import app.morphe.manager.ui.screen.shared.rememberMorpheLogoBitmap
import app.morphe.manager.util.isolateLtr
import app.morphe.manager.util.toast
import org.koin.compose.koinInject

/**
 * About section: the app itself, its changelog, the logs a bug report asks for and the tour.
 */
@Composable
fun AboutSection(
    onAboutClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onExportDebugLogs: () -> Unit,
    onStartTour: (() -> Unit)? = null,
    networkInfo: NetworkInfo = koinInject()
) {
    val context = LocalContext.current
    val noNetworkToast = stringResource(R.string.no_network_toast)
    val logo = rememberMorpheLogoBitmap()

    SettingsGroup {
        SettingsItem(
            onClick = onAboutClick,
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.version) + " " + BuildConfig.VERSION_NAME.isolateLtr(),
            leadingContent = {
                logo?.let {
                    Icon(
                        bitmap = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Defaults.IconSize)
                    )
                }
            }
        )

        SettingsDivider()

        SettingsItem(
            icon = Icons.AutoMirrored.Outlined.Article,
            title = stringResource(R.string.changelog),
            subtitle = stringResource(R.string.changelog_description),
            onClick = {
                if (!networkInfo.isConnected()) {
                    context.toast(noNetworkToast)
                    return@SettingsItem
                }
                onChangelogClick()
            }
        )

        SettingsDivider()

        // Next to the changelog rather than with the backups: logs are what a bug report asks for
        SettingsItem(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.settings_system_export_debug_logs),
            subtitle = stringResource(R.string.settings_system_export_debug_logs_description),
            onClick = onExportDebugLogs
        )

        if (onStartTour != null) {
            SettingsDivider()

            SettingsItem(
                icon = Icons.Outlined.Lightbulb,
                title = stringResource(R.string.onboarding_restart_title),
                subtitle = stringResource(R.string.onboarding_restart_desc),
                onClick = onStartTour
            )
        }
    }
}
