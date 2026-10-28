/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.repository.StorageStats
import app.morphe.manager.ui.screen.settings.SettingsSubpage
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.util.formatBytes
import app.morphe.manager.util.openAppDetailsSettings
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Where the manager's storage goes and what clears it, on a page of its own, see
 * [SettingsSubpage.STORAGE]. The saved APKs of each kind open over it, see [ApkManagementPage].
 * A search of the settings only goes through whether APKs are kept, the rest being no setting.
 */
@Composable
fun StorageSettings(viewModel: StorageManagementViewModel = koinViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var apkType by rememberSaveable { mutableStateOf<ApkManagementType?>(null) }

    DialogHost(target = apkType) { type ->
        ApkManagementPage(type = type, onDismiss = { apkType = null })
    }

    if (LocalSettingsSearch.current != null) {
        SavedApksSection(viewModel = viewModel, stats = stats, onOpen = { apkType = it })
    } else {
        StorageOverview(
            viewModel = viewModel,
            stats = stats,
            apksOpen = apkType != null,
            onOpenApks = { apkType = it }
        )
    }
}

/** How much the manager holds and in what, with whether APKs are kept and what clears the rest. */
@Composable
private fun StorageOverview(
    viewModel: StorageManagementViewModel,
    stats: StorageStats,
    apksOpen: Boolean,
    onOpenApks: (ApkManagementType) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages = koinInject<UserMessages>()
    var showClearAllConfirm by remember { mutableStateOf(false) }
    val patchInProgress by viewModel.patchInProgress.collectAsStateWithLifecycle()
    // A clear that would pull a running patch's files from under it, held until the user confirms
    var guardedClear by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun clearGuarded(title: String, clear: () -> Unit) {
        if (patchInProgress) guardedClear = title to clear else clear()
    }
    val patchRunningWarning = stringResource(R.string.settings_system_storage_patch_running_warning)
    val clearAnywayText = stringResource(R.string.settings_system_storage_clear_anyway)
    val patcherWorkspaceTitle = stringResource(R.string.settings_system_storage_patcher_workspace_title)
    val temporaryTitle = stringResource(R.string.settings_system_storage_temporary_title)

    val nothingToClearText = stringResource(R.string.settings_system_storage_nothing_to_clear)
    val clearedTemplate = stringResource(R.string.settings_system_storage_cleared)

    val onCleared: (Long) -> Unit = { freed ->
        val message = if (freed <= 0L) nothingToClearText
        else clearedTemplate.format(context.formatBytes(freed))
        messages.show(message)
    }

    // Read again whenever the page comes back into view, from the saved APKs as from elsewhere,
    // as either may have freed or taken up room meanwhile
    LaunchedEffect(lifecycleOwner, apksOpen) {
        if (apksOpen) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() }
    }

    DialogHost(target = guardedClear) { (title, clear) ->
        ConfirmDialog(
            title = title,
            message = patchRunningWarning,
            primaryText = clearAnywayText,
            onConfirm = {
                guardedClear = null
                clear()
            },
            onDismiss = { guardedClear = null }
        )
    }

    DialogHost(visible = showClearAllConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_storage_clear_all),
            message = stringResource(R.string.settings_system_storage_clear_all_confirm),
            primaryText = if (patchInProgress) clearAnywayText else stringResource(R.string.clear),
            notice = patchRunningWarning.takeIf { patchInProgress },
            onConfirm = {
                showClearAllConfirm = false
                viewModel.clearAllCaches(onCleared)
            },
            onDismiss = { showClearAllConfirm = false },
            // Empty caches free nothing, so they are left out
            items = listOf(
                Triple(MaterialSymbols.Rounded.Cloud_download, R.string.settings_system_storage_http_cache_title, stats.httpCacheBytes),
                Triple(MaterialSymbols.Rounded.Share, R.string.settings_system_storage_installer_cache_title, stats.installerShareBytes),
                Triple(MaterialSymbols.Rounded.Build, R.string.settings_system_storage_patcher_workspace_title, stats.patcherWorkspaceBytes),
                Triple(MaterialSymbols.Rounded.Hourglass_empty, R.string.settings_system_storage_temporary_title, stats.temporaryBytes)
            ).filter { (_, _, bytes) -> bytes > 0 }.map { (icon, title, bytes) ->
                ConfirmItem(icon, stringResource(title), context.formatBytes(bytes))
            }
        )
    }

    // Nothing has been read until the first reading lands, and a real one always has room free
    val loading = stats == StorageStats.Empty

    SettingsColumn {
        SettingsGroup {
            SettingsItemCard(onClick = null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Defaults.SpaceLarge),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
                ) {
                    // How much Morphe holds against what is left free, heading the breakdown of it.
                    // The free space is left out where the device does not report it
                    val free = when {
                        loading -> "…"
                        stats.deviceFreeBytes > 0L -> context.formatBytes(stats.deviceFreeBytes)
                        else -> null
                    }
                    InfoStatBox(
                        value = if (loading) "…" else context.formatBytes(stats.appUsedBytes),
                        subtitle = free?.let { stringResource(R.string.settings_system_storage_free, it) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                    StorageHistogram(segments = stats.toSegments(), loading = loading)
                }
            }
        }

        SavedApksSection(viewModel = viewModel, stats = stats, onOpen = onOpenApks)

        SettingsSection(
            title = stringResource(R.string.settings_system_storage_caches),
            icon = MaterialSymbols.Rounded.Cached
        ) {
            SettingsGroup {
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Cloud_download,
                    accentColor = StorageColors.HttpCache,
                    title = stringResource(R.string.settings_system_storage_http_cache_title),
                    description = stringResource(R.string.settings_system_storage_http_cache_description),
                    bytes = stats.httpCacheBytes,
                    onClear = { viewModel.clearHttpCache(onCleared) }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Share,
                    accentColor = StorageColors.InstallerShare,
                    title = stringResource(R.string.settings_system_storage_installer_cache_title),
                    description = stringResource(R.string.settings_system_storage_installer_cache_description),
                    bytes = stats.installerShareBytes,
                    onClear = { viewModel.clearInstallerShareCache(onCleared) }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Build,
                    accentColor = StorageColors.PatcherWorkspace,
                    title = patcherWorkspaceTitle,
                    description = stringResource(R.string.settings_system_storage_patcher_workspace_description),
                    bytes = stats.patcherWorkspaceBytes,
                    onClear = { clearGuarded(patcherWorkspaceTitle) { viewModel.clearPatcherWorkspace(onCleared) } }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Hourglass_empty,
                    accentColor = StorageColors.Temporary,
                    title = temporaryTitle,
                    description = stringResource(R.string.settings_system_storage_temporary_description),
                    bytes = stats.temporaryBytes,
                    onClear = { clearGuarded(temporaryTitle) { viewModel.clearTemporary(onCleared) } }
                )
            }
            // Apart from the caches it sums up, so it does not read as one more of them
            SettingsGroup {
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Delete_sweep,
                    accentColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.settings_system_storage_clear_all),
                    description = stringResource(R.string.settings_system_storage_clear_all_description),
                    bytes = stats.totalCacheBytes,
                    onClear = { showClearAllConfirm = true }
                )
            }
        }

        SettingsGroup {
            SettingsItem(
                onClick = { context.openAppDetailsSettings() },
                title = stringResource(R.string.settings_system_storage_open_app_info_title),
                subtitle = stringResource(R.string.settings_system_storage_open_app_info_description),
                leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Phone_android) },
                // Leaves for the system settings rather than going on within the manager
                trailingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Open_in_new.autoMirrored) }
            )
        }
    }
}

/**
 * Whether original and patched APKs are kept, each over the row opening those already kept.
 *
 * @param stats How much the kept APKs take up, as last read.
 */
@Composable
private fun SavedApksSection(
    viewModel: StorageManagementViewModel,
    stats: StorageStats,
    onOpen: (ApkManagementType) -> Unit
) {
    val context = LocalContext.current
    val saveOriginalApks by viewModel.prefs.saveOriginalApks.getAsState()
    val savePatchedApks by viewModel.prefs.savePatchedApks.getAsState()
    // Nothing has been read until the first reading lands, and a real one always has room free
    val loading = stats == StorageStats.Empty

    SettingsSection(
        title = stringResource(R.string.settings_system_storage_saved_apks),
        icon = MaterialSymbols.Rounded.Inventory_2
    ) {
        SavedApksGroup(
            icon = MaterialSymbols.Rounded.File_download,
            accentColor = StorageColors.OriginalApks,
            saveTitle = stringResource(R.string.settings_system_save_original_apks_title),
            saveDescription = stringResource(R.string.settings_system_save_original_apks_description),
            save = saveOriginalApks,
            onToggleSave = { viewModel.setSaveOriginalApks(!saveOriginalApks) },
            title = stringResource(R.string.settings_system_original_apks_title),
            size = context.formatBytes(stats.originalApksBytes).takeUnless { loading },
            onOpen = { onOpen(ApkManagementType.ORIGINAL) }
        )
        SavedApksGroup(
            icon = MaterialSymbols.Rounded.Apps,
            accentColor = StorageColors.PatchedApks,
            saveTitle = stringResource(R.string.settings_system_save_patched_apks_title),
            saveDescription = stringResource(R.string.settings_system_save_patched_apks_description),
            save = savePatchedApks,
            onToggleSave = { viewModel.setSavePatchedApks(!savePatchedApks) },
            title = stringResource(R.string.settings_system_patched_apks_title),
            size = context.formatBytes(stats.patchedApksBytes).takeUnless { loading },
            onOpen = { onOpen(ApkManagementType.PATCHED) }
        )
    }
}

/**
 * Whether APKs of one kind are kept, over the row opening those already kept. The row stays while
 * keeping is off, as what was kept before is still there to clear.
 *
 * @param size How much the kept APKs take up, null while it is still being read.
 */
@Composable
private fun SavedApksGroup(
    icon: ImageVector,
    accentColor: Color,
    saveTitle: String,
    saveDescription: String,
    save: Boolean,
    onToggleSave: () -> Unit,
    title: String,
    size: String?,
    onOpen: () -> Unit
) {
    SettingsGroup {
        SettingsSwitchItem(
            checked = save,
            onToggle = onToggleSave,
            leadingContent = { ThemedIcon(icon = icon, tint = accentColor) },
            title = saveTitle,
            subtitle = saveDescription
        )
        SettingsItem(
            onClick = onOpen,
            icon = MaterialSymbols.Rounded.Folder_open,
            title = title,
            subtitle = size ?: "…"
        )
    }
}

@Composable
private fun CacheActionRow(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    description: String,
    bytes: Long,
    onClear: () -> Unit
) {
    SettingsItemCard(onClick = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Defaults.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
        ) {
            IconTextRow(
                leadingContent = { ThemedIcon(icon = icon, tint = accentColor) },
                title = title,
                description = description
            )
            CardActionRow(
                actions = listOf(
                    CardAction(
                        icon = MaterialSymbols.Rounded.Delete_sweep,
                        label = "${stringResource(R.string.clear)} (${LocalContext.current.formatBytes(bytes)})",
                        onClick = onClear,
                        enabled = bytes > 0L,
                        destructive = true
                    )
                )
            )
        }
    }
}

@Composable
private fun StorageStats.toSegments(): List<StorageSegment> {
    val originalLabel = stringResource(R.string.settings_system_original_apks_title)
    val patchedLabel = stringResource(R.string.settings_system_patched_apks_title)
    val bundlesLabel = stringResource(R.string.settings_system_storage_patch_bundles_title)
    val keystoreLabel = stringResource(R.string.settings_system_storage_keystore_title)
    val appDataLabel = stringResource(R.string.settings_system_storage_app_data_title)
    val httpLabel = stringResource(R.string.settings_system_storage_http_cache_title)
    val shareLabel = stringResource(R.string.settings_system_storage_installer_cache_title)
    val patcherLabel = stringResource(R.string.settings_system_storage_patcher_workspace_title)
    val tempLabel = stringResource(R.string.settings_system_storage_temporary_title)
    return listOf(
        StorageSegment("original", originalLabel, originalApksBytes, StorageColors.OriginalApks),
        StorageSegment("patched", patchedLabel, patchedApksBytes, StorageColors.PatchedApks),
        StorageSegment("bundles", bundlesLabel, patchBundlesBytes, StorageColors.PatchBundles),
        StorageSegment("keystore", keystoreLabel, keystoreBytes, StorageColors.Keystore),
        StorageSegment("appdata", appDataLabel, appDataBytes, StorageColors.AppData),
        StorageSegment("http", httpLabel, httpCacheBytes, StorageColors.HttpCache),
        StorageSegment("share", shareLabel, installerShareBytes, StorageColors.InstallerShare),
        StorageSegment("patcher", patcherLabel, patcherWorkspaceBytes, StorageColors.PatcherWorkspace),
        StorageSegment("temp", tempLabel, temporaryBytes, StorageColors.Temporary)
    )
}
