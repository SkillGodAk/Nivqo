/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A path whose delete never carries out, standing in for storage that refuses the removal. */
private class UndeletableFile(file: File) : File(file.path) {
    override fun delete() = false
}

class RetainedCopyDeletionTest {
    private val dir = Files.createTempDirectory("morphe-retained-copies").toFile()

    private fun copy(name: String) = dir.resolve(name).apply { writeText("apk") }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `copies taken off storage are not reported`() {
        val files = listOf(copy("first.apk"), copy("second.apk"))

        assertEquals(emptyList(), deleteRetainedCopies(files))
        assertTrue(files.none { it.exists() })
    }

    @Test
    fun `a copy that survives the attempt is reported, so its record can be kept`() {
        val stubborn = UndeletableFile(copy("stubborn.apk"))

        assertEquals(listOf(stubborn), deleteRetainedCopies(listOf(copy("gone.apk"), stubborn)))
        assertTrue(stubborn.exists())
    }

    @Test
    fun `a path cleared by something else counts as removed`() {
        val alreadyGone = UndeletableFile(dir.resolve("never-written.apk"))

        assertEquals(emptyList(), deleteRetainedCopies(listOf(alreadyGone)))
    }
}
