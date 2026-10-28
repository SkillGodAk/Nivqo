/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PatchOptionKeys
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.HIDE_SHORTS_APP_SHORTCUT_DESC
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.HIDE_SHORTS_APP_SHORTCUT_TITLE
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.HIDE_SHORTS_WIDGET_DESC
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.HIDE_SHORTS_WIDGET_TITLE
import app.morphe.manager.domain.manager.getLocalizedOrCustomText
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.HomeViewModel
import app.morphe.manager.ui.viewmodel.PatchOptionsViewModel
import app.morphe.manager.util.KnownApps
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * Advanced patch options section.
 * Options are dynamically loaded from the patch bundle repository.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PatchOptionsSection(
    patchOptionsPrefs: PatchOptionsPreferencesManager,
    patchOptionsViewModel: PatchOptionsViewModel,
    homeViewModel: HomeViewModel
) {
    // Collect patch options from ViewModel
    val youtubePatches by patchOptionsViewModel.youtubePatches.collectAsState()
    val youtubeMusicPatches by patchOptionsViewModel.youtubeMusicPatches.collectAsState()
    val loadError = patchOptionsViewModel.loadError

    // Track bundle update progress to show loading state
    val bundleUpdateProgress by homeViewModel.patchBundleRepository.bundleUpdateProgress.collectAsStateWithLifecycle(null)
    val isBundleUpdating = bundleUpdateProgress != null && bundleUpdateProgress!!.result == PatchBundleRepository.BundleUpdateResult.None

    val bundleInfo by homeViewModel.patchBundleRepository.bundleInfoFlow
        .collectAsStateWithLifecycle(emptyMap())

    // Refresh only once when bundle info first becomes available.
    // Using a flag avoids re-triggering refresh on every recomposition or tab switch
    val hasRefreshed = remember { mutableStateOf(false) }
    LaunchedEffect(bundleInfo) {
        if (bundleInfo.isNotEmpty() && !hasRefreshed.value) {
            hasRefreshed.value = true
            patchOptionsViewModel.refresh()
        }
    }

    // Derived in composition so Compose observes the patch flows and `isLoading`,
    // otherwise the empty-state stays latched until the next recomposition trigger
    val noPatchesAvailable = !isBundleUpdating && !patchOptionsViewModel.isLoading &&
            loadError == null &&
            youtubePatches.isEmpty() && youtubeMusicPatches.isEmpty()

    DialogHost(target = patchOptionsViewModel.showBrandingDialogFor) { packageName ->
        CustomBrandingDialog(
            patchOptionsPrefs = patchOptionsPrefs,
            patchOptionsViewModel = patchOptionsViewModel,
            packageName = packageName,
            onDismiss = { patchOptionsViewModel.dismissBrandingDialog() }
        )
    }

    DialogHost(target = patchOptionsViewModel.showHeaderDialogFor) { packageName ->
        CustomHeaderDialog(
            patchOptionsPrefs = patchOptionsPrefs,
            patchOptionsViewModel = patchOptionsViewModel,
            packageName = packageName,
            onDismiss = { patchOptionsViewModel.dismissHeaderDialog() }
        )
    }

    // Only the options themselves are what a search of the settings finds here
    val searching = LocalSettingsSearch.current?.isActive == true

    SettingsColumn {
        when {
            searching -> PatchOptionGroups(
                youtubePatches.isNotEmpty(),
                youtubeMusicPatches.isNotEmpty(),
                patchOptionsPrefs,
                patchOptionsViewModel
            )

            isBundleUpdating -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Defaults.SpaceLarge),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingIndicator(modifier = Modifier.size(Defaults.SpaceExtraExtraLarge))
                    Spacer(modifier = Modifier.width(Defaults.SpaceMedium))
                    Text(
                        text = stringResource(R.string.settings_advanced_patch_options_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            noPatchesAvailable -> EmptyState(
                message = stringResource(R.string.settings_advanced_patch_options_waiting_for_source),
                icon = MaterialSymbols.Rounded.Hourglass_empty
            )

            loadError != null -> EmptyState(
                message = stringResource(R.string.settings_advanced_patch_options_load_error),
                icon = MaterialSymbols.Rounded.Error,
                subtitle = loadError
            )

            else -> {
                PatchOptionGroups(
                    youtubePatches.isNotEmpty(),
                    youtubeMusicPatches.isNotEmpty(),
                    patchOptionsPrefs,
                    patchOptionsViewModel
                )
                SettingsFooter(text = stringResource(R.string.settings_advanced_patch_options_restart_message))
            }
        }
    }
}

/** The options of each app the sources patch, each app a section of its own. */
@Composable
private fun PatchOptionGroups(
    hasYoutube: Boolean,
    hasYoutubeMusic: Boolean,
    patchOptionsPrefs: PatchOptionsPreferencesManager,
    patchOptionsViewModel: PatchOptionsViewModel
) {
    if (hasYoutube) {
        AppPatchOptionsSection(
            packageName = KnownApps.YOUTUBE,
            icon = MaterialSymbols.Rounded.Video_library,
            patchOptionsViewModel = patchOptionsViewModel
        ) {
            HideShortsGroup(patchOptionsPrefs = patchOptionsPrefs, viewModel = patchOptionsViewModel)
        }
    }

    if (hasYoutubeMusic) {
        AppPatchOptionsSection(
            packageName = KnownApps.YOUTUBE_MUSIC,
            icon = MaterialSymbols.Rounded.Library_music,
            patchOptionsViewModel = patchOptionsViewModel
        )
    }
}

/**
 * The patch options one app offers, as a section headed by the app: its branding and header, then
 * any [moreGroups] of options only that app has.
 */
@Composable
private fun AppPatchOptionsSection(
    packageName: String,
    icon: ImageVector,
    patchOptionsViewModel: PatchOptionsViewModel,
    moreGroups: @Composable () -> Unit = {}
) {
    val hasBranding = patchOptionsViewModel.getBrandingOptions(packageName) != null
    val hasHeader = patchOptionsViewModel.getHeaderOptions(packageName) != null

    SettingsSection(title = KnownApps.getAppName(packageName), icon = icon) {
        SettingsGroup {
            // Custom Branding
            if (hasBranding) {
                SettingsItem(
                    icon = MaterialSymbols.Rounded.Style,
                    title = stringResource(R.string.settings_advanced_patch_options_custom_branding),
                    subtitle = stringResource(R.string.settings_advanced_patch_options_custom_branding_description),
                    onClick = { patchOptionsViewModel.openBrandingDialog(packageName) }
                )
            }

            // Custom Header
            if (hasHeader) {
                SettingsItem(
                    icon = MaterialSymbols.Rounded.Image,
                    title = stringResource(R.string.settings_advanced_patch_options_custom_header),
                    subtitle = stringResource(R.string.settings_advanced_patch_options_custom_header_description),
                    onClick = { patchOptionsViewModel.openHeaderDialog(packageName) }
                )
            }

            // Show message if no options available for this app
            if (!hasBranding && !hasHeader) {
                SettingsItemCard(onClick = null) {
                    Text(
                        text = stringResource(R.string.settings_advanced_patch_options_no_available),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Defaults.SpaceLarge)
                    )
                }
            }
        }
        moreGroups()
    }
}

/** The Shorts entry points YouTube's patches can hide, a group of their own under its options. */
@Composable
private fun HideShortsGroup(
    patchOptionsPrefs: PatchOptionsPreferencesManager,
    viewModel: PatchOptionsViewModel
) {
    val context = LocalContext.current
    val hideShortsOptions = viewModel.getHideShortsOptions()

    val hasAppShortcutOption = viewModel.hasOption(hideShortsOptions, PatchOptionKeys.HIDE_SHORTS_APP_SHORTCUT)
    val hasWidgetOption = viewModel.hasOption(hideShortsOptions, PatchOptionKeys.HIDE_SHORTS_WIDGET)

    if (!hasAppShortcutOption && !hasWidgetOption) return

    val appShortcutOption = viewModel.getOption(hideShortsOptions, PatchOptionKeys.HIDE_SHORTS_APP_SHORTCUT)
    val widgetOption = viewModel.getOption(hideShortsOptions, PatchOptionKeys.HIDE_SHORTS_WIDGET)

    SettingsGroup {
        // Hide App Shortcut
        if (hasAppShortcutOption && appShortcutOption != null) {
            val hideShortsAppShortcut by patchOptionsPrefs.hideShortsAppShortcut.getAsState()
            val title = getLocalizedOrCustomText(
                context,
                appShortcutOption.title,
                HIDE_SHORTS_APP_SHORTCUT_TITLE,
                R.string.settings_advanced_patch_options_hide_shorts_app_shortcut
            )
            val description = getLocalizedOrCustomText(
                context,
                appShortcutOption.description,
                HIDE_SHORTS_APP_SHORTCUT_DESC,
                R.string.settings_advanced_patch_options_hide_shorts_app_shortcut_description
            )

            SettingsSwitchItem(
                checked = hideShortsAppShortcut,
                onToggle = { viewModel.toggleHideShortsAppShortcut(patchOptionsPrefs, hideShortsAppShortcut) },
                icon = MaterialSymbols.Rounded.App_shortcut,
                title = title,
                subtitle = description
            )
        }

        // Hide Widget
        if (hasWidgetOption && widgetOption != null) {
            val hideShortsWidget by patchOptionsPrefs.hideShortsWidget.getAsState()
            val title = getLocalizedOrCustomText(
                context,
                widgetOption.title,
                HIDE_SHORTS_WIDGET_TITLE,
                R.string.settings_advanced_patch_options_hide_shorts_widget
            )
            val description = getLocalizedOrCustomText(
                context,
                widgetOption.description,
                HIDE_SHORTS_WIDGET_DESC,
                R.string.settings_advanced_patch_options_hide_shorts_widget_description
            )

            SettingsSwitchItem(
                checked = hideShortsWidget,
                onToggle = { viewModel.toggleHideShortsWidget(patchOptionsPrefs, hideShortsWidget) },
                icon = MaterialSymbols.Rounded.Widgets,
                title = title,
                subtitle = description
            )
        }
    }
}
