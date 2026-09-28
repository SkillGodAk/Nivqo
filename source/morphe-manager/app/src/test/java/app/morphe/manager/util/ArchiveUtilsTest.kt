/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import java.io.File
import kotlin.test.*

/**
 * This check is the last thing between a download that merely transferred cleanly and a file that
 * gets installed. It earns its place from a real case: an API answered a download with its own
 * JSON metadata, which the manager then stored as a patch bundle without noticing.
 */
class ArchiveUtilsTest {
    private val temporaryFiles = mutableListOf<File>()

    private fun fileOf(vararg bytes: Int): File =
        File.createTempFile("archive-test", null)
            .also { temporaryFiles += it }
            .apply { writeBytes(bytes.map(Int::toByte).toByteArray()) }

    private fun fileOf(content: String): File =
        File.createTempFile("archive-test", null)
            .also { temporaryFiles += it }
            .apply { writeText(content) }

    @AfterTest
    fun cleanUp() {
        temporaryFiles.forEach { it.delete() }
    }

    @Test
    fun `zip local file header is accepted`() {
        // Every patch bundle and APK starts with these four bytes
        assertTrue(fileOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00).hasZipHeader())
    }

    @Test
    fun `api metadata is rejected`() {
        val metadata = """{"url":"https://api.github.com/repos/o/r/releases/assets/1","name":"patches.mpp"}"""
        assertFalse(fileOf(metadata).hasZipHeader())
    }

    @Test
    fun `captive portal html is rejected`() {
        assertFalse(fileOf("<!DOCTYPE html><html><body>Sign in to continue</body></html>").hasZipHeader())
    }

    @Test
    fun `empty archive marker is rejected`() {
        // PK is an empty zip: a valid archive, but never a real bundle
        assertFalse(fileOf(0x50, 0x4B, 0x05, 0x06).hasZipHeader())
    }

    @Test
    fun `truncated and empty files are rejected`() {
        assertFalse(fileOf(0x50, 0x4B).hasZipHeader())
        assertFalse(fileOf("").hasZipHeader())
    }

    @Test
    fun `missing file is rejected rather than thrown`() {
        val missing = File.createTempFile("archive-test", null).also { it.delete() }
        assertFalse(missing.hasZipHeader())
    }
}
