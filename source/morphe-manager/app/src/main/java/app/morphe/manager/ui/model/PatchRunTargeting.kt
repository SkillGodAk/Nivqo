/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

import app.morphe.manager.patcher.patch.PatchInfo
import app.morphe.manager.util.PatchSelection

/**
 * Option key a patch declares the package name it builds under. The manager has no way to know
 * which patch renames an app, but the name that patch was given is right there to be read.
 */
const val PACKAGE_NAME_OPTION_KEY = "packageName"

/** Whether this patch is one that builds the app under a package name of its own. */
val PatchInfo.declaresPackageName: Boolean
    get() = options?.any { it.key == PACKAGE_NAME_OPTION_KEY } == true

/**
 * Whether this patch names the app it builds without being asked to, so selecting it is by itself
 * enough to produce a clone.
 *
 * A patch that picks the name itself ships a default to fall back on, while one taking the name
 * only as an override leaves it unset and reads the app's own instead. Patch authors are free to
 * break that convention, so this carries no further than the label on a patch in the list.
 */
val PatchInfo.renamesByDefault: Boolean
    get() = options?.firstOrNull { it.key == PACKAGE_NAME_OPTION_KEY }?.default != null

/**
 * Whether a run builds a copy of the app rather than the install the app itself has.
 *
 * A package name of its own is what a copy needs, not what makes it one: patches rename an app
 * for reasons of their own, and such a build is still the app's only install. What separates the
 * two is who asked for the rename, and a patch that renames only ends up in [selection] when the
 * user selected it. A patch pulled in as a dependency renames just the same and is never in there.
 */
internal fun producesClone(
    originalPackageName: String,
    resultPackageName: String,
    selection: PatchSelection,
    declaresPackageName: (bundleUid: Int, patchName: String) -> Boolean
): Boolean = resultPackageName != originalPackageName &&
        selection.any { (bundleUid, patchNames) ->
            patchNames.any { declaresPackageName(bundleUid, it) }
        }

/**
 * A finished build that answers to a package the run was not aimed at, so installing it adds a
 * clone rather than updating the app it was built from.
 *
 * A patch names the app it builds in its own code, so this is read off the output once the run
 * is over and holds however the patch arrived at the name.
 *
 * @param replacesExisting Whether the device already has an install under [resultPackageName],
 *   which is the case the user has most reason to hear about: it is about to be overwritten.
 */
data class RenameWarning(
    val targetPackageName: String,
    val resultPackageName: String,
    val replacesExisting: Boolean
)

/**
 * The package a run reads its saved patches and options from.
 *
 * Patches belong to the install they were applied to, so rebuilding one reads what that install
 * was built with. Anything else reads the app itself: a run producing a separate install starts
 * from what the app was last patched with, and so does an install that predates configurations
 * of their own.
 */
internal fun configurationKey(
    originalPackageName: String,
    repatchedPackageName: String?,
    repatchedHasOwnConfiguration: Boolean
): String = when {
    repatchedPackageName == null || repatchedPackageName == originalPackageName -> originalPackageName
    repatchedHasOwnConfiguration -> repatchedPackageName
    else -> originalPackageName
}
