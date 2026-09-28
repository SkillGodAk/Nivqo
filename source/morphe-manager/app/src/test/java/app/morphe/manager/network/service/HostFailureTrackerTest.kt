/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.service

import kotlin.test.*

/**
 * The tracker decides whether a source gets its full retry cycle or a single attempt, so both
 * mistakes are expensive: holding a recovered host back stops downloads that would work, and
 * forgetting a dead one makes every source in a batch pay the whole backoff again.
 */
class HostFailureTrackerTest {
    private var clock = 0L
    private val tracker = HostFailureTracker(ttlMillis = 60_000L) { clock }

    @Test
    fun `unknown host is not failing`() {
        assertFalse(tracker.isFailing("github.com"))
    }

    @Test
    fun `null host is never failing`() {
        // Callers that could not resolve a host must keep their normal retries
        assertFalse(tracker.isFailing(null))
    }

    @Test
    fun `marked host stays failing for the whole window`() {
        tracker.markFailed("github.com")

        assertTrue(tracker.isFailing("github.com"))
        clock += 59_000L
        assertTrue(tracker.isFailing("github.com"))
    }

    @Test
    fun `verdict expires after the window`() {
        tracker.markFailed("github.com")
        clock += 60_001L

        assertFalse(tracker.isFailing("github.com"))
    }

    @Test
    fun `success clears the verdict immediately`() {
        tracker.markFailed("github.com")
        tracker.clear("github.com")

        assertFalse(tracker.isFailing("github.com"))
    }

    @Test
    fun `hosts are tracked independently`() {
        tracker.markFailed("github.com")

        assertTrue(tracker.isFailing("github.com"))
        assertFalse(tracker.isFailing("gitlab.com"))
        assertFalse(tracker.isFailing("raw.githubusercontent.com"))
    }

    @Test
    fun `re-marking restarts the window`() {
        tracker.markFailed("github.com")
        clock += 59_000L
        tracker.markFailed("github.com")
        clock += 59_000L

        assertTrue(tracker.isFailing("github.com"))
    }
}
