/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

import app.morphe.manager.patcher.patch.Option
import app.morphe.manager.patcher.patch.PatchInfo
import kotlinx.collections.immutable.toImmutableList
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CAKE = "me.mycake"
private const val CLONE = "me.mycake.morphe"
private const val RENAMED = "app.morphe.mycake"
private const val BUNDLE = 7
private const val CLONE_PATCH = "Clone app"
private const val OTHER_PATCH = "Enable Plus"

class ConfigurationKeyTest {
    @Test
    fun `a run aimed at no install reads what the app was last patched with`() {
        assertEquals(
            CAKE,
            configurationKey(
                originalPackageName = CAKE,
                repatchedPackageName = null,
                repatchedHasOwnConfiguration = false
            )
        )
    }

    @Test
    fun `rebuilding the app's own install reads the app`() {
        assertEquals(
            CAKE,
            configurationKey(
                originalPackageName = CAKE,
                repatchedPackageName = CAKE,
                repatchedHasOwnConfiguration = true
            )
        )
    }

    @Test
    fun `rebuilding a copy reads what that copy was built with`() {
        assertEquals(
            CLONE,
            configurationKey(
                originalPackageName = CAKE,
                repatchedPackageName = CLONE,
                repatchedHasOwnConfiguration = true
            )
        )
    }

    @Test
    fun `a copy with nothing saved of its own falls back to the app`() {
        assertEquals(
            CAKE,
            configurationKey(
                originalPackageName = CAKE,
                repatchedPackageName = CLONE,
                repatchedHasOwnConfiguration = false
            )
        )
    }
}

/**
 * The rule the patch list labels a renaming patch by. Only a badge rides on it, but one that
 * fires on every patch merely reading the package name teaches the user to ignore it.
 */
class RenamesByDefaultTest {
    private fun patchWithPackageNameOption(default: String?) = PatchInfo(
        name = CLONE_PATCH,
        description = null,
        include = true,
        compatiblePackages = null,
        options = listOf(
            Option(
                title = PACKAGE_NAME_OPTION_KEY,
                key = PACKAGE_NAME_OPTION_KEY,
                description = "",
                required = false,
                type = typeOf<String>(),
                default = default,
                presets = null,
                validator = { true }
            )
        ).toImmutableList()
    )

    @Test
    fun `a patch carrying a name to fall back on renames on its own`() {
        assertTrue(patchWithPackageNameOption("Default").renamesByDefault)
    }

    @Test
    fun `a patch that only accepts an override reads the app's own name`() {
        assertFalse(patchWithPackageNameOption(null).renamesByDefault)
    }

    @Test
    fun `a patch with no package name option at all never renames`() {
        val patch = PatchInfo(
            name = OTHER_PATCH,
            description = null,
            include = true,
            compatiblePackages = null,
            options = null
        )
        assertFalse(patch.renamesByDefault)
    }
}

class ProducesCloneTest {
    private fun producedClone(
        resultPackageName: String,
        selectedPatches: Set<String> = setOf(CLONE_PATCH)
    ) = producesClone(
        originalPackageName = CAKE,
        resultPackageName = resultPackageName,
        selection = mapOf(BUNDLE to selectedPatches),
        declaresPackageName = { _, patchName -> patchName == CLONE_PATCH }
    )

    @Test
    fun `a selected renaming patch builds a copy`() {
        assertTrue(producedClone(CLONE))
    }

    @Test
    fun `a rename no selected patch asked for is the app's own install`() {
        assertFalse(producedClone(RENAMED, selectedPatches = setOf(OTHER_PATCH)))
    }

    @Test
    fun `a run that kept the app's name built no copy whatever it selected`() {
        assertFalse(producedClone(CAKE))
    }
}
