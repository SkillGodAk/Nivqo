/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Returns all external storage volumes as (isPrimary, rootDir) pairs.
 * isPrimary=true for Internal Storage, false for SD cards.
 */
fun Context.externalStorageVolumes(): List<Pair<Boolean, File>> {
    val primary = Environment.getExternalStorageDirectory()
    val dirs = runCatching { getExternalFilesDirs(null).filterNotNull() }.getOrDefault(emptyList())
    return dirs.map { dir ->
        val isPrimary = dir.absolutePath.startsWith(primary.absolutePath)
        val root = if (isPrimary) primary else File(dir.absolutePath.substringBefore("/Android/data/"))
        isPrimary to root
    }
}
