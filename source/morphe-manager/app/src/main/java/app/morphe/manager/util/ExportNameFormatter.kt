/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/util/ExportNameFormatter.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.util

import app.morphe.manager.patcher.patch.PatchSourceRef

data class PatchedAppExportData(
    val appName: String?,
    val packageName: String,
    val appVersion: String?,
    val sources: List<PatchSourceRef> = emptyList()
)

object ExportNameFormatter {
    /** Sources named in the file name, the rest only counted, so it stays within filesystem limits. */
    private const val NAMED_SOURCES = 2

    /**
     * The name a patched APK is saved under, such as `YouTube-v20.1.0-Morphe_Patches-v1.2.0.apk`.
     * Every source names the version it patched with, as builds from different sources of the
     * same app are otherwise told apart by nothing.
     */
    fun format(data: PatchedAppExportData): String {
        // An app name made only of symbols would leave nothing before the version
        val app = data.appName?.let { FilenameUtils.sanitize(it, keepScripts = true) }?.takeUnless { it.isEmpty() }
            ?: FilenameUtils.sanitize(data.packageName)
        return listOfNotNull(app, formatVersion(data.appVersion) ?: "unknown", formatSources(data.sources))
            .joinToString("-", postfix = ".apk")
    }

    private fun formatSources(sources: List<PatchSourceRef>): String? {
        val named = sources
            .mapNotNull { source ->
                FilenameUtils.sanitize(source.name, keepScripts = true).takeUnless { it.isEmpty() }
                    ?.let { name -> listOfNotNull(name, formatVersion(source.version)).joinToString("-") }
            }
            .distinct()
            .takeUnless { it.isEmpty() }
            ?: return null
        val hidden = named.size - NAMED_SOURCES
        return (named.take(NAMED_SOURCES) + listOfNotNull(hidden.takeIf { it > 0 }?.toString()))
            .joinToString("+")
    }

    // Lowercased first, so a "V" prefix goes too
    private fun formatVersion(raw: String?): String? =
        raw?.trim()?.lowercase()?.normalizeVersion()
            ?.let { FilenameUtils.sanitize(it) }
            ?.takeUnless { it.isEmpty() }
            ?.let { "v$it" }
}
