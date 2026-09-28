/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedAppLabelTest {
    @Test
    fun `a read label is kept`() {
        assertEquals(
            "Cake",
            rememberedAppLabel(
                readLabel = "Cake",
                packageName = "me.mycake",
                previousLabel = null
            )
        )
    }

    @Test
    fun `a read label replaces the previous one`() {
        assertEquals(
            "Cake Plus",
            rememberedAppLabel(
                readLabel = "Cake Plus",
                packageName = "me.mycake",
                previousLabel = "Cake"
            )
        )
    }

    @Test
    fun `an unreadable label leaves the previous one in place`() {
        assertEquals(
            "Cake",
            rememberedAppLabel(
                readLabel = null,
                packageName = "me.mycake",
                previousLabel = "Cake"
            )
        )
    }

    @Test
    fun `a label that is the package name does not overwrite the previous one`() {
        assertEquals(
            "Cake",
            rememberedAppLabel(
                readLabel = "me.mycake",
                packageName = "me.mycake",
                previousLabel = "Cake"
            )
        )
    }

    @Test
    fun `a blank label does not overwrite the previous one`() {
        assertEquals(
            "Cake",
            rememberedAppLabel(
                readLabel = "   ",
                packageName = "me.mycake",
                previousLabel = "Cake"
            )
        )
    }

    @Test
    fun `nothing to read and nothing remembered stays empty`() {
        assertNull(
            rememberedAppLabel(
                readLabel = "me.mycake",
                packageName = "me.mycake",
                previousLabel = null
            )
        )
    }
}
