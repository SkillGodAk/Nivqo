/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PatchOptionKeys
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.CUSTOM_HEADER_INSTRUCTION
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager.Companion.CUSTOM_ICON_INSTRUCTION
import app.morphe.manager.domain.manager.getLocalizedOrCustomText
import app.morphe.manager.patcher.patch.ExplicitOptionKind
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.OptionInfo
import app.morphe.manager.ui.viewmodel.PatchOptionsViewModel
import app.morphe.manager.util.rememberFolderPickerWithPermission
import app.morphe.manager.util.toFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Custom branding dialog with folder picker and adaptive icon creator.
 */
@Composable
fun CustomBrandingDialog(
    patchOptionsPrefs: PatchOptionsPreferencesManager,
    patchOptionsViewModel: PatchOptionsViewModel,
    packageName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Get current values from preferences
    val appName = remember { mutableStateOf(patchOptionsPrefs.customAppName(packageName).getBlocking()) }
    val iconPath = remember { mutableStateOf(patchOptionsPrefs.customIconPath(packageName).getBlocking()) }
    val appIconStyle = remember { mutableStateOf(patchOptionsPrefs.appIconStyle(packageName).getBlocking()) }

    // State for icon creator dialog
    val showIconCreator = remember { mutableStateOf(false) }

    // Get branding options from bundle
    val brandingOptions = patchOptionsViewModel.getBrandingOptions(packageName)
    val appNameOption = patchOptionsViewModel.getOption(brandingOptions, PatchOptionKeys.CUSTOM_NAME)
    val iconOption = patchOptionsViewModel.getOption(brandingOptions, PatchOptionKeys.CUSTOM_ICON)
    val appIconStyles = patchOptionsViewModel
        .getOption(brandingOptions, PatchOptionKeys.APP_ICON)
        ?.presets
        .orEmpty()

    // The custom icon style has no images of its own, so the patch fails the run without a folder
    val missingCustomIcon = appIconStyle.value == PatchOptionKeys.APP_ICON_CUSTOM &&
            iconPath.value.isBlank()

    // Folder picker with permission handling (needs permissions for icon creation)
    val openFolderPicker = rememberFolderPickerWithPermission(
        onFolderPicked = { uri ->
            // Convert URI to path for patch options compatibility
            iconPath.value = uri.toFilePath()
        }
    )

    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_advanced_patch_options_custom_branding),
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.save),
                onPrimaryClick = {
                    patchOptionsViewModel.saveCustomBranding(
                        prefs = patchOptionsPrefs,
                        packageName = packageName,
                        appName = appName.value,
                        iconPath = iconPath.value,
                        appIconStyle = appIconStyle.value,
                        onDone = onDismiss
                    )
                },
                primaryEnabled = !missingCustomIcon,
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // App name field
            if (appNameOption != null) {
                AppDialogTextField(
                    value = appName.value,
                    onValueChange = { appName.value = it },
                    label = { Text(stringResource(R.string.settings_advanced_patch_options_custom_branding_app_name)) },
                    placeholder = { Text(stringResource(R.string.settings_advanced_patch_options_custom_branding_app_name_hint)) },
                    showClearButton = true
                )
            }

            // App icon style, the icon the patched app is built with
            if (appIconStyles.isNotEmpty()) {
                DropdownOptionItem(
                    title = stringResource(R.string.settings_advanced_patch_options_custom_branding_app_icon),
                    description = stringResource(R.string.settings_advanced_patch_options_custom_branding_app_icon_description),
                    value = appIconStyle.value,
                    presets = appIconStyles,
                    // The patch builds only the icon styles it ships
                    allowCustomValue = false,
                    onValueChange = { appIconStyle.value = it?.toString().orEmpty() }
                )

                if (missingCustomIcon) {
                    Notice(
                        text = stringResource(R.string.settings_advanced_patch_options_custom_branding_app_icon_needs_folder),
                        tone = SemanticTone.Error,
                        density = NoticeDensity.Compact
                    )
                }
            }

            // Icon path field with folder picker
            if (iconOption != null) {
                FolderOptionInput(
                    option = iconOption,
                    value = iconPath.value,
                    label = stringResource(R.string.settings_advanced_patch_options_custom_branding_custom_icon),
                    placeholder = "/storage/emulated/0/icons",
                    isInvalid = missingCustomIcon,
                    onValueChange = { iconPath.value = it },
                    onPickFolder = { openFolderPicker() }
                )

                // Create icon button
                AppDialogOutlinedButton(
                    text = stringResource(R.string.adaptive_icon_create),
                    onClick = { showIconCreator.value = true },
                    icon = Icons.Outlined.AutoAwesome,
                    modifier = Modifier.fillMaxWidth()
                )

                // Expandable instructions section
                iconOption.description.let { description ->
                    ExpandableSurface(
                        title = stringResource(R.string.patch_option_instructions),
                        content = {
                            ScrollableInstruction(
                                description = getLocalizedOrCustomText(
                                    context,
                                    description,
                                    CUSTOM_ICON_INSTRUCTION,
                                    R.string.settings_advanced_patch_options_custom_branding_custom_icon_instruction
                                )
                            )
                        }
                    )
                }
            }

            // Show message if no options available
            if (appNameOption == null && iconOption == null && appIconStyles.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_advanced_patch_options_no_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalDialogSecondaryTextColor.current.copy(alpha = 0.7f),
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }

    // Icon creator dialog
    if (showIconCreator.value) {
        AdaptiveIconCreatorDialog(
            packageName = packageName,
            onDismiss = { showIconCreator.value = false },
            onIconCreated = { path ->
                iconPath.value = path
                showIconCreator.value = false
            }
        )
    }
}

/**
 * Folder input for a patch option. Typed folder options render as a picker button,
 * matching the patch options shown during patching. Plain untyped string options
 * declared by older patch bundles keep the editable path field.
 */
@Composable
private fun FolderOptionInput(
    option: OptionInfo,
    value: String,
    label: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    isInvalid: Boolean = false
) {
    val isGone = rememberPathIsGone(value)
    val isMissing = isInvalid || isGone || (option.required && value.isBlank())

    if (option.explicitKind == ExplicitOptionKind.Folder) {
        PickerFieldHeader(
            title = label,
            required = option.required,
            isInvalid = isMissing
        )

        PickerButtonRow(
            label = stringResource(R.string.select_folder),
            selectedPath = value,
            icon = Icons.Outlined.Folder,
            onPick = onPickFolder,
            onClear = { onValueChange("") }
        )
    } else {
        AppDialogTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            isError = isMissing,
            showClearButton = true,
            onFolderPickerClick = onPickFolder
        )
    }

    // The patcher fails a run over a folder that is gone, and this is where it can still be fixed
    if (isGone) {
        Notice(
            text = stringResource(R.string.settings_advanced_patch_options_path_gone),
            tone = SemanticTone.Error,
            density = NoticeDensity.Compact
        )
    }
}

/**
 * Whether [path] is set but nothing can be read there anymore. Checked off the main thread, and
 * again whenever the path changes.
 */
@Composable
private fun rememberPathIsGone(path: String): Boolean {
    var isGone by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        // Only an absolute path can be checked, anything else is for the patch to make sense of
        isGone = path.startsWith("/") && withContext(Dispatchers.IO) { !File(path).canRead() }
    }

    return isGone
}

/**
 * Custom header dialog with folder picker and dynamic instructions from bundle.
 */
@Composable
fun CustomHeaderDialog(
    patchOptionsPrefs: PatchOptionsPreferencesManager,
    patchOptionsViewModel: PatchOptionsViewModel,
    packageName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // State for header creator dialog
    val headerPath = remember {
        mutableStateOf(patchOptionsPrefs.customHeaderPath(packageName).getBlocking())
    }

    val showHeaderCreator = remember { mutableStateOf(false) }

    // Get header options from bundle
    val headerOptions = patchOptionsViewModel.getHeaderOptions(packageName)
    val customOption = patchOptionsViewModel.getOption(headerOptions, PatchOptionKeys.CUSTOM_HEADER)

    // Folder picker with permission handling (needs permissions for header creation)
    val openFolderPicker = rememberFolderPickerWithPermission(
        onFolderPicked = { uri ->
            // Convert URI to path for patch options compatibility
            headerPath.value = uri.toFilePath()
        }
    )

    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_advanced_patch_options_custom_header),
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.save),
                onPrimaryClick = {
                    patchOptionsViewModel.saveCustomHeader(
                        prefs = patchOptionsPrefs,
                        packageName = packageName,
                        headerPath = headerPath.value,
                        onDone = onDismiss
                    )
                },
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (customOption != null) {
                FolderOptionInput(
                    option = customOption,
                    value = headerPath.value,
                    label = stringResource(R.string.settings_advanced_patch_options_custom_header),
                    placeholder = "/storage/emulated/0/header",
                    onValueChange = { headerPath.value = it },
                    onPickFolder = { openFolderPicker() }
                )

                // Create header button
                AppDialogOutlinedButton(
                    text = stringResource(R.string.header_creator_create),
                    onClick = { showHeaderCreator.value = true },
                    icon = Icons.Outlined.Image,
                    modifier = Modifier.fillMaxWidth()
                )

                // Expandable instructions section
                customOption.description.let { description ->
                    ExpandableSurface(
                        title = stringResource(R.string.patch_option_instructions),
                        content = {
                            ScrollableInstruction(
                                description = getLocalizedOrCustomText(
                                    context,
                                    description,
                                    CUSTOM_HEADER_INSTRUCTION,
                                    R.string.settings_advanced_patch_options_custom_header_instruction
                                )
                            )
                        }
                    )
                }
            } else {
                // No option available
                Text(
                    text = stringResource(R.string.settings_advanced_patch_options_no_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalDialogSecondaryTextColor.current.copy(alpha = 0.7f),
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }

    // Header creator dialog
    if (showHeaderCreator.value) {
        HeaderCreatorDialog(
            packageName = packageName,
            onDismiss = { showHeaderCreator.value = false },
            onHeaderCreated = { path ->
                headerPath.value = path
                showHeaderCreator.value = false
            }
        )
    }
}
