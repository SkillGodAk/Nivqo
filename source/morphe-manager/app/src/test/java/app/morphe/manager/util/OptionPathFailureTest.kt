/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import app.morphe.manager.patcher.patch.CompatiblePackage
import app.morphe.manager.patcher.patch.Option
import app.morphe.manager.patcher.patch.PatchBundleInfo
import app.morphe.manager.patcher.patch.PatchInfo
import kotlinx.collections.immutable.persistentListOf
import kotlin.io.path.createTempDirectory
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a run is handed once a path option leads nowhere. A saved configuration is written per
 * bundle by replacing all of it, so what this leaves behind decides whether a dropped value is
 * really gone or comes back the next time the app is patched.
 */
class OptionPathFailureTest {
    private val brandingIcon = PathValidationResult(
        patchName = "Custom branding",
        optionKey = "customIcon",
        path = "/storage/emulated/0/icons",
        reason = PathValidationResult.Reason.Missing
    )

    @Test
    fun `the option behind the failing path is dropped`() {
        val options = mapOf(
            0 to mapOf(
                "Custom branding" to mapOf(
                    "customIcon" to "/storage/emulated/0/icons",
                    "customName" to "Morphe"
                )
            )
        )

        assertEquals(
            mapOf(0 to mapOf("Custom branding" to mapOf("customName" to "Morphe"))),
            options.withoutFailingPaths(listOf(brandingIcon))
        )
    }

    @Test
    fun `a bundle left with nothing stays, so the saved copy is cleared too`() {
        val options = mapOf(
            0 to mapOf("Custom branding" to mapOf("customIcon" to "/storage/emulated/0/icons"))
        )

        val remaining = options.withoutFailingPaths(listOf(brandingIcon))

        assertTrue(remaining.containsKey(0))
        assertTrue(remaining.getValue(0).getValue("Custom branding").isEmpty())
    }

    @Test
    fun `every bundle that set the option loses it`() {
        val options = mapOf(
            0 to mapOf("Custom branding" to mapOf("customIcon" to "/storage/emulated/0/icons")),
            1 to mapOf("Custom branding" to mapOf("customIcon" to "/storage/emulated/0/icons"))
        )

        assertTrue(
            options.withoutFailingPaths(listOf(brandingIcon))
                .values
                .all { patches -> patches.getValue("Custom branding").isEmpty() }
        )
    }

    @Test
    fun `nothing is rebuilt when no path failed`() {
        val options = mapOf(0 to mapOf("Custom branding" to mapOf("customName" to "Morphe")))

        assertSame(options, options.withoutFailingPaths(emptyList()))
    }
}

/**
 * A path the app can read is still put to the check its option declares, so a folder without what
 * the patch needs is caught before the run rather than failing it.
 */
class OptionPathValidationTest {
    private val folder = createTempDirectory().toFile().apply { deleteOnExit() }

    private fun headerPatch(packageName: String?, validator: (String?) -> Boolean) = PatchInfo(
        name = "Change header",
        description = null,
        include = true,
        compatiblePackages = packageName?.let {
            persistentListOf(CompatiblePackage(packageName = it, versions = null))
        },
        options = persistentListOf(
            Option(
                title = "Custom header logo",
                key = "custom",
                description = "",
                required = false,
                type = typeOf<String>(),
                default = null,
                presets = null,
                validator = validator
            )
        )
    )

    private fun bundle(validator: (String?) -> Boolean) = bundle(headerPatch(null, validator))

    private fun bundle(vararg patches: PatchInfo) = PatchBundleInfo.Scoped(
        name = "Source",
        version = null,
        uid = 0,
        enabled = true,
        patches = patches.toList(),
        compatible = emptyList(),
        incompatible = emptyList(),
        universal = emptyList()
    )

    // Only a path starting at the root is checked, which a Windows host reaches without its drive
    private val path = folder.invariantSeparatorsPath.substringAfter(':')

    private val options = mapOf(0 to mapOf("Change header" to mapOf<String, Any?>("custom" to path)))

    @Test
    fun `a folder the option turns down is rejected`() {
        assertEquals(
            listOf(PathValidationResult.Reason.Rejected),
            validateOptionPaths(options, listOf(bundle { false }), APP).map { it.reason }
        )
    }

    @Test
    fun `a folder the option takes passes`() {
        assertTrue(validateOptionPaths(options, listOf(bundle { it == path }), APP).isEmpty())
    }

    @Test
    fun `a check that throws turns the folder down`() {
        assertEquals(
            listOf(PathValidationResult.Reason.Rejected),
            validateOptionPaths(options, listOf(bundle { error("unreadable") }), APP).map { it.reason }
        )
    }

    @Test
    fun `an option no bundle declares is only checked for being there`() {
        assertTrue(validateOptionPaths(options, emptyList(), APP).isEmpty())
    }

    @Test
    fun `a patch of the same name for another app does not judge the folder`() {
        val bundle = bundle(
            headerPatch(APP) { it == path },
            headerPatch("com.google.android.youtube") { false }
        )

        assertTrue(validateOptionPaths(options, listOf(bundle), APP).isEmpty())
    }

    private companion object {
        const val APP = "com.google.android.apps.youtube.music"
    }
}