/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The changelogs here take the shapes semantic-release leaves in Morphe repositories: the dev one
 * read up to its last stable release, and the stable one carrying every dev build merged into a
 * release under it.
 */
class ChangelogSinceTest {
    private fun entry(version: String, date: String) = ChangelogEntry(version, date, content = "")

    private val dev = listOf(
        entry("1.46.0-dev.2", "2026-10-04"),
        entry("1.46.0-dev.1", "2026-10-03"),
        entry("1.45.0", "2026-10-02")
    )

    private val stable = listOf(
        entry("1.45.0", "2026-10-02"),
        entry("1.45.0-dev.2", "2026-10-01"),
        entry("1.45.0-dev.1", "2026-09-30"),
        entry("1.44.0", "2026-09-21"),
        entry("1.44.0-dev.1", "2026-09-20"),
        entry("1.43.0", "2026-09-14"),
        entry("1.42.0", "2026-09-09")
    )

    private fun List<ChangelogEntry>.versions() = map { it.version }

    @Test
    fun `a dev changelog stops above a version older than its last stable release`() {
        assertTrue(ChangelogParser.stopsAbove(dev, "1.43.0"))
        assertTrue(ChangelogParser.stopsAbove(dev, "1.45.0-dev.1"))
        assertFalse(ChangelogParser.stopsAbove(dev, "1.45.0"))
        assertFalse(ChangelogParser.stopsAbove(dev, "1.46.0-dev.1"))
        assertFalse(ChangelogParser.stopsAbove(dev, null))
    }

    @Test
    fun `a changelog ending on a dev build does not stop above anything`() {
        assertFalse(ChangelogParser.stopsAbove(dev.take(2), "1.0.0"))
    }

    @Test
    fun `an older version reaches into the stable history, leaving its dev builds out`() {
        assertEquals(
            listOf("1.46.0-dev.2", "1.46.0-dev.1", "1.45.0", "1.44.0"),
            ChangelogParser.entriesSince(dev, "1.43.0", stable).versions()
        )
    }

    @Test
    fun `a version at or past the last stable release needs no stable history`() {
        assertEquals(
            listOf("1.46.0-dev.2", "1.46.0-dev.1"),
            ChangelogParser.entriesSince(dev, "1.45.0", stable).versions()
        )
        assertEquals(
            listOf("1.46.0-dev.2"),
            ChangelogParser.entriesSince(dev, "1.46.0-dev.1", stable).versions()
        )
    }

    @Test
    fun `a dev build of the last stable release gets that release`() {
        assertEquals(
            listOf("1.46.0-dev.2", "1.46.0-dev.1", "1.45.0"),
            ChangelogParser.entriesSince(dev, "1.45.0-dev.1", stable).versions()
        )
    }

    @Test
    fun `without the stable history the dev changelog alone is what is known`() {
        assertEquals(
            dev.versions(),
            ChangelogParser.entriesSince(dev, "1.43.0").versions()
        )
    }

    @Test
    fun `the stable channel takes the releases newer than the version`() {
        val channel = stable.filterNot { it.isPrerelease }
        assertEquals(
            listOf("1.45.0", "1.44.0"),
            ChangelogParser.entriesSince(channel, "1.43.0").versions()
        )
        assertEquals(
            listOf("1.45.0"),
            ChangelogParser.entriesSince(channel, "1.45.0-dev.1").versions()
        )
    }
}
