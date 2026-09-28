/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.patch

import app.morphe.patcher.patch.InstallerType

/**
 * UI-facing lock state derived from a patch's availability resolver for the current install target.
 *
 * Not stored anywhere; computed on demand by [PatchInfo.lockState] and consumed by the expert-mode
 * dialog to disable checkboxes and surface the reason the patch cannot be toggled.
 */
enum class PatchLockState {
    /** Patch can be toggled freely by the user. */
    NONE,

    /** Patch is required for the current install target and cannot be unchecked. */
    LOCKED_ON,

    /** Patch is not available for the current install target and cannot be checked. */
    LOCKED_OFF,
}

/**
 * Whether a tap on a patch that is currently [selected] has to be ignored.
 *
 * A required patch may still be put into the selection, only taking it back out is refused. That
 * keeps the one way back open for a patch the rules left out of a bundle the run does not use yet.
 */
fun PatchLockState.blocksToggle(selected: Boolean) = when (this) {
    PatchLockState.NONE       -> false
    PatchLockState.LOCKED_ON  -> selected
    PatchLockState.LOCKED_OFF -> true
}

/**
 * Install target handed to availability resolvers. Only mount vs non-mount is known while patches
 * are being selected; the concrete non-mount installer is picked after patching.
 */
fun installerTypeFor(useMount: Boolean) = if (useMount) InstallerType.MOUNT else InstallerType.STANDARD

