/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import app.morphe.manager.data.room.apps.installed.SelectionPayload
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deleting a source cascades the applied patches away, so the record written at patch time is the
 * only attribution left. Whatever this returns is shown to the user, never an internal uid.
 */
class AppliedBundleAttributionTest {
    private fun recorded(
        bundleUid: Int = CUSTOM_SOURCE_UID,
        bundleName: String? = "Hoodles",
        bundleVersion: String? = "1.40.0"
    ) = SelectionPayload.BundleSelection(
        bundleUid = bundleUid,
        patches = listOf("Patch"),
        bundleName = bundleName,
        bundleVersion = bundleVersion
    )

    private fun resolve(
        sourceTitle: String? = null,
        bundleName: String? = null,
        bundleVersion: String? = null,
        storedVersion: String? = null,
        recorded: SelectionPayload.BundleSelection? = recorded()
    ) = resolveAppliedBundleAttribution(
        sourceTitle = sourceTitle,
        bundleName = bundleName,
        bundleVersion = bundleVersion,
        storedVersion = storedVersion,
        recorded = recorded,
        fallbackTitle = FALLBACK
    )

    @Test
    fun `a deleted source keeps the name and version it was patched with`() {
        val attribution = resolve()

        assertEquals("Hoodles", attribution.title)
        assertEquals("1.40.0", attribution.version)
    }

    @Test
    fun `a negative uid is never part of the title`() {
        val attribution = resolve(recorded = recorded(bundleName = null, bundleVersion = null))

        assertEquals(FALLBACK, attribution.title)
        assertEquals(null, attribution.version)
    }

    @Test
    fun `a live source outranks what was recorded`() {
        val attribution = resolve(
            sourceTitle = "Hoodles renamed",
            bundleName = "Hoodles",
            storedVersion = "1.41.0"
        )

        assertEquals("Hoodles renamed", attribution.title)
        assertEquals("1.41.0", attribution.version)
    }

    @Test
    fun `the recorded version outranks the version the bundle carries now`() {
        val attribution = resolve(bundleName = "Hoodles", bundleVersion = "2.0.0")

        assertEquals("1.40.0", attribution.version)
    }

    @Test
    fun `blank attribution falls through instead of being displayed`() {
        val attribution = resolve(
            sourceTitle = "",
            recorded = recorded(bundleName = "", bundleVersion = "")
        )

        assertEquals(FALLBACK, attribution.title)
        assertEquals(null, attribution.version)
    }

    @Test
    fun `an app patched before the name was recorded still resolves`() {
        val attribution = resolve(recorded = null)

        assertEquals(FALLBACK, attribution.title)
        assertEquals(null, attribution.version)
    }

    private companion object {
        // Custom sources get a random signed integer, so the uid is regularly negative
        const val CUSTOM_SOURCE_UID = -1234567890
        const val FALLBACK = "Deleted patch source"
    }
}
