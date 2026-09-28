/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.domain.bundles.AppVersionStatus
import app.morphe.manager.ui.model.HomeAppItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeAppFilterModeTest {
    @Test
    fun `confirmed replacement is historically patched and currently installed`() {
        val item = HomeAppItem(
            id = "app.example",
            packageName = "app.example",
            displayName = "Example",
            gradientColors = emptyList(),
            installedApp = InstalledApp(
                currentPackageName = "app.example",
                originalPackageName = "app.example",
                version = "1.0",
                installType = InstallType.DEFAULT
            ),
            packageInfo = null,
            isPinnedByDefault = false,
            isInstalledOnDevice = true,
            isDeleted = false,
            isInstallStateNotPatched = true,
            isInstallStateUnknown = false,
            isInstallStatePending = false,
            savedApkFile = null,
            hasUpdate = false,
            versionStatus = null,
            patchCount = 0,
            isClone = false
        )

        assertTrue(HomeAppFilterMode.PATCHED.matches(item))
        assertTrue(HomeAppFilterMode.INSTALLED.matches(item))
        assertFalse(HomeAppFilterMode.NOT_PATCHED.matches(item))
        assertFalse(HomeAppFilterMode.UNINSTALLED.matches(item))
    }

    @Test
    fun `pending verification stays physically installed without showing an update verdict`() {
        val item = HomeAppItem(
            id = "app.example",
            packageName = "app.example",
            displayName = "Example",
            gradientColors = emptyList(),
            installedApp = InstalledApp(
                currentPackageName = "app.example",
                originalPackageName = "app.example",
                version = "1.0",
                installType = InstallType.DEFAULT
            ),
            packageInfo = null,
            isPinnedByDefault = false,
            isInstalledOnDevice = true,
            isDeleted = false,
            isInstallStateNotPatched = false,
            isInstallStateUnknown = false,
            isInstallStatePending = true,
            savedApkFile = null,
            hasUpdate = true,
            versionStatus = null,
            patchCount = 0,
            isClone = false
        )

        assertTrue(HomeAppFilterMode.INSTALLED.matches(item))
        assertFalse(HomeAppFilterMode.UNINSTALLED.matches(item))
        assertFalse(item.showsUpdateBadge)
    }

    @Test
    fun `a clone is told apart from the app it was built from`() {
        val app = item(id = "app.example", isClone = false)
        val clone = item(id = "app.example.morphe", isClone = true)

        assertTrue(HomeAppFilterMode.CLONES.matches(clone))
        assertFalse(HomeAppFilterMode.CLONES.matches(app))
        // The two share a package name, so the filter has to read the card rather than the app
        assertTrue(HomeAppFilterMode.PATCHED.matches(clone))
        assertTrue(HomeAppFilterMode.PATCHED.matches(app))
    }

    @Test
    fun `a newer supported version badges the card without queueing a repatch`() {
        val item = item(id = "app.example", isClone = false).copy(
            versionStatus = AppVersionStatus("9.12.51", "9.13.50", isBehind = true)
        )

        assertTrue(item.showsVersionBadge)
        assertTrue(item.showsRebuildBadge)
        // Patching at a version the sources still cover returns the install unchanged, so the
        // repatch count and the update sort are left out of it
        assertFalse(item.showsUpdateBadge)
    }

    @Test
    fun `an install past what the sources cover is left to the state it is in`() {
        val item = item(id = "app.example", isClone = false).copy(
            versionStatus = AppVersionStatus("9.14.10", "9.13.50", isBehind = false)
        )

        assertFalse(item.showsVersionBadge)
        assertFalse(item.showsRebuildBadge)
    }

    private fun item(id: String, isClone: Boolean) = HomeAppItem(
        id = id,
        packageName = "app.example",
        displayName = "Example",
        gradientColors = emptyList(),
        installedApp = InstalledApp(
            currentPackageName = id,
            originalPackageName = "app.example",
            version = "1.0",
            installType = InstallType.DEFAULT
        ),
        packageInfo = null,
        isPinnedByDefault = false,
        isInstalledOnDevice = true,
        isDeleted = false,
        isInstallStateNotPatched = false,
        isInstallStateUnknown = false,
        isInstallStatePending = false,
        savedApkFile = null,
        hasUpdate = false,
        versionStatus = null,
        patchCount = 0,
        isClone = isClone
    )
}
