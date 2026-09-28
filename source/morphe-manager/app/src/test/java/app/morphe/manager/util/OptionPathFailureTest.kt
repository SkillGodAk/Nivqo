/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

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
