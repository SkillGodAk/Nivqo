/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.manager

import android.content.Context
import app.morphe.manager.domain.manager.base.BasePreferencesManager
import app.morphe.manager.domain.manager.base.StringPreference
import app.morphe.manager.util.KnownApps

/**
 * Option keys declared by the patches, exactly as the bundle spells them.
 * Used both to address a patch option and to build the storage key of its saved value.
 */
object PatchOptionKeys {
    const val CUSTOM_NAME = "customName"
    const val CUSTOM_ICON = "customIcon"
    const val APP_ICON = "appIcon"
    const val CUSTOM_HEADER = "custom"
    const val HIDE_SHORTS_APP_SHORTCUT = "hideShortsAppShortcut"
    const val HIDE_SHORTS_WIDGET = "hideShortsWidget"

    /** Value of [APP_ICON] that builds the app with the icon supplied through [CUSTOM_ICON]. */
    const val APP_ICON_CUSTOM = "custom"
}

/**
 * Manages patch-specific option values that are applied during patching.
 * This manager only stores the values - the available options are fetched
 * dynamically from the patch bundle repository.
 *
 * Storage keys follow the pattern: {package}_{patchName}_{optionKey}
 */
class PatchOptionsPreferencesManager(
    context: Context
) : BasePreferencesManager(context, "patch_options") {

    companion object {
        // Patch names (must match exactly with bundle)
        const val PATCH_CUSTOM_BRANDING = "Custom branding"
        const val PATCH_CHANGE_HEADER = "Change header"
        const val PATCH_HIDE_SHORTS = "Hide Shorts components"

        // Hide Shorts options
        const val HIDE_SHORTS_APP_SHORTCUT_TITLE = "Hide Shorts app shortcut"
        const val HIDE_SHORTS_APP_SHORTCUT_DESC = "Permanently hides the shortcut to open Shorts when long pressing the app icon in your launcher."
        const val HIDE_SHORTS_WIDGET_TITLE = "Hide Shorts widget"
        const val HIDE_SHORTS_WIDGET_DESC = "Permanently hides the launcher widget Shorts button."

        // Custom branding icon instructions
        const val CUSTOM_ICON_INSTRUCTION = """Folder with images to use as a custom icon.

The folder must contain one or more of the following folders, depending on the DPI of the device:
- mipmap-mdpi
- mipmap-hdpi
- mipmap-xhdpi
- mipmap-xxhdpi
- mipmap-xxxhdpi

Each of the folders must contain all of the following files:
morphe_adaptive_background_custom.png
morphe_adaptive_foreground_custom.png

The image dimensions must be as follows:
- mipmap-mdpi: 108x108 px
- mipmap-hdpi: 162x162 px
- mipmap-xhdpi: 216x216 px
- mipmap-xxhdpi: 324x324 px
- mipmap-xxxhdpi: 432x432 px

Optionally, the path contains a 'drawable' folder with any of the monochrome icon files:
morphe_adaptive_monochrome_custom.xml
morphe_notification_icon_custom.xml"""

        // Custom header instructions
        const val CUSTOM_HEADER_INSTRUCTION = """Folder with images to use as a custom header logo.

The folder must contain one or more of the following folders, depending on the DPI of the device:
- drawable-hdpi
- drawable-xhdpi
- drawable-xxhdpi
- drawable-xxxhdpi

Each of the folders must contain all of the following files:
morphe_header_custom_light.png
morphe_header_custom_dark.png 

The image dimensions must be as follows:
- drawable-hdpi: 194x72 px
- drawable-xhdpi: 258x96 px
- drawable-xxhdpi: 387x144 px
- drawable-xxxhdpi: 512x192 px"""
    }

    /** Value of a string patch option, blank when the user left it unset. */
    private fun optionValue(packageName: String, patchName: String, optionKey: String) =
        stringPreference("${packageName}_${patchName}_${optionKey}", "")

    // Custom Branding - App Name
    fun customAppName(packageName: String) =
        optionValue(packageName, PATCH_CUSTOM_BRANDING, PatchOptionKeys.CUSTOM_NAME)

    // Custom Branding - Icon Path
    fun customIconPath(packageName: String) =
        optionValue(packageName, PATCH_CUSTOM_BRANDING, PatchOptionKeys.CUSTOM_ICON)

    // Custom Branding - App icon style, blank means the patch picks the icon itself
    fun appIconStyle(packageName: String) =
        optionValue(packageName, PATCH_CUSTOM_BRANDING, PatchOptionKeys.APP_ICON)

    // Change Header - Custom Header Path
    fun customHeaderPath(packageName: String) =
        optionValue(packageName, PATCH_CHANGE_HEADER, PatchOptionKeys.CUSTOM_HEADER)

    // Hide Shorts - App Shortcut (YouTube only)
    val hideShortsAppShortcut = booleanPreference(
        "${KnownApps.YOUTUBE}_${PATCH_HIDE_SHORTS}_${PatchOptionKeys.HIDE_SHORTS_APP_SHORTCUT}",
        false
    )

    // Hide Shorts - Widget (YouTube only)
    val hideShortsWidget = booleanPreference(
        "${KnownApps.YOUTUBE}_${PATCH_HIDE_SHORTS}_${PatchOptionKeys.HIDE_SHORTS_WIDGET}",
        false
    )

    /**
     * Forgets a saved option value, so the patch runs with its own default instead.
     * Used to drop a path that no longer leads anywhere, which the patcher would fail the run over.
     */
    suspend fun clearOptionValue(packageName: String, patchName: String, optionKey: String) =
        optionValue(packageName, patchName, optionKey).update("")

    /**
     * Export patch options for a given package.
     * Format: Map<BundleUid, Map<PatchName, Map<OptionKey, Value>>>
     *
     * An option the user left unset is left out entirely, so the patch keeps its own default
     * instead of receiving a blank value it would reject.
     */
    suspend fun exportPatchOptions(packageName: String): Map<Int, Map<String, Map<String, Any?>>> {
        val bundleOptions = mutableMapOf<String, MutableMap<String, Any?>>()

        // Adds a value to the options of a patch, skipping the ones left unset
        suspend fun putIfSet(patchName: String, optionKey: String, value: StringPreference) {
            val stored = value.get().takeIf { it.isNotBlank() } ?: return
            bundleOptions.getOrPut(patchName) { mutableMapOf() }[optionKey] = stored
        }

        // Custom Branding patch options
        putIfSet(PATCH_CUSTOM_BRANDING, PatchOptionKeys.CUSTOM_NAME, customAppName(packageName))
        putIfSet(PATCH_CUSTOM_BRANDING, PatchOptionKeys.CUSTOM_ICON, customIconPath(packageName))
        putIfSet(PATCH_CUSTOM_BRANDING, PatchOptionKeys.APP_ICON, appIconStyle(packageName))

        // Change Header patch options
        putIfSet(PATCH_CHANGE_HEADER, PatchOptionKeys.CUSTOM_HEADER, customHeaderPath(packageName))

        // Hide Shorts patch options (YouTube only)
        if (packageName == KnownApps.YOUTUBE) {
            val shortsOptions = mutableMapOf<String, Any?>()
            if (hideShortsAppShortcut.get()) shortsOptions[PatchOptionKeys.HIDE_SHORTS_APP_SHORTCUT] = true
            if (hideShortsWidget.get()) shortsOptions[PatchOptionKeys.HIDE_SHORTS_WIDGET] = true
            if (shortsOptions.isNotEmpty()) bundleOptions[PATCH_HIDE_SHORTS] = shortsOptions
        }

        // Bundle ID 0 = default Morphe bundle
        return if (bundleOptions.isEmpty()) emptyMap() else mapOf(0 to bundleOptions)
    }
}

/**
 * Gets localized text if it matches original English text, otherwise returns the custom text from patch.
 */
fun getLocalizedOrCustomText(
    context: Context,
    currentText: String,
    originalEnglishText: String,
    localizedResId: Int
): String {
    return if (currentText == originalEnglishText) {
        context.getString(localizedResId)
    } else {
        currentText
    }
}
