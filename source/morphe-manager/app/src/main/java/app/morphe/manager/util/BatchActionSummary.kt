/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.Context
import androidx.annotation.PluralsRes
import app.morphe.manager.R

fun Context.batchActionSummary(
    @PluralsRes completedActionPluralRes: Int,
    completed: Int,
    skipped: Int,
    mounted: Int = 0
): String? {
    val parts = buildList {
        if (completed > 0) {
            add(resources.getQuantityString(completedActionPluralRes, completed, completed.toString()))
        }
        if (mounted > 0) {
            add(resources.getQuantityString(R.plurals.batch_mount_summary, mounted, mounted.toString()))
        }
        if (skipped > 0) {
            add(resources.getQuantityString(R.plurals.batch_skipped_summary, skipped, skipped.toString()))
        }
    }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
