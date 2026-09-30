/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.split

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Split preparation events cross from the patcher process to the app as a type name and a module
 * name, and have to arrive as the event that was sent.
 */
class SplitPreparationEventWireTest {
    private fun roundTrip(event: SplitPreparationEvent) = SplitPreparationEvent.fromWire(
        event.wireType,
        (event as? SplitPreparationEvent.Merging)?.apkName
    )

    @Test
    fun `every event arrives as the one that was sent`() {
        listOf(
            SplitPreparationEvent.Extracting,
            SplitPreparationEvent.Merging("split_config.arm64_v8a.apk"),
            SplitPreparationEvent.Writing,
            SplitPreparationEvent.Finalizing
        ).forEach { event ->
            assertEquals(event, roundTrip(event))
        }
    }

    @Test
    fun `a type this build does not know is dropped`() {
        assertNull(SplitPreparationEvent.fromWire("Verifying", null))
        assertNull(SplitPreparationEvent.fromWire(null, null))
    }
}
