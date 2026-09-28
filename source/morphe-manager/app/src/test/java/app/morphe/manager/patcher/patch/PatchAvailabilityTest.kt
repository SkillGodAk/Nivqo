/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.patch

import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.AvailabilityResolver
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rules a patch's availability is read by. They decide which checkboxes the user is allowed to
 * touch, so getting them wrong either strands a patch in the selection or drops one silently.
 */
class PatchAvailabilityTest {
    private val installer = InstallerType.STANDARD
    private val architecture = ApkArchitecture.ARM64_V8A

    private fun patch(include: Boolean = false, availability: AvailabilityResolver? = null) = PatchInfo(
        name = "Patch",
        description = null,
        include = include,
        compatiblePackages = null,
        options = null,
        availabilityResolver = availability
    )

    @Test
    fun `a patch without a resolver falls back to its own default`() {
        assertEquals(true, patch(include = true).defaultSelected(installer, architecture))
        assertEquals(false, patch(include = false).defaultSelected(installer, architecture))
        assertEquals(
            PatchLockState.NONE,
            patch(include = true).lockState(installer, architecture)
        )
    }

    @Test
    fun `a required patch starts out selected whether or not it locks`() {
        val patch = patch { _, _ -> PatchAvailability.REQUIRED }

        assertEquals(true, patch.defaultSelected(installer, architecture))
        assertEquals(PatchLockState.LOCKED_ON, patch.lockState(installer, architecture))
        assertEquals(
            PatchLockState.NONE,
            patch.lockState(installer, architecture, enforceRequired = false)
        )
    }

    @Test
    fun `an unavailable patch stays locked off even across several bundles`() {
        val patch = patch(include = true) { _, _ -> PatchAvailability.UNAVAILABLE }

        assertEquals(false, patch.defaultSelected(installer, architecture))
        assertEquals(
            PatchLockState.LOCKED_OFF,
            patch.lockState(installer, architecture, enforceRequired = false)
        )
    }

    @Test
    fun `availability answers per install target`() {
        val gmsCore = patch { installerType, _ ->
            if (installerType == InstallerType.MOUNT) {
                PatchAvailability.UNAVAILABLE
            } else {
                PatchAvailability.REQUIRED
            }
        }

        assertEquals(
            PatchLockState.LOCKED_ON,
            gmsCore.lockState(InstallerType.STANDARD, architecture)
        )
        assertEquals(
            PatchLockState.LOCKED_OFF,
            gmsCore.lockState(InstallerType.MOUNT, architecture)
        )
    }
}
