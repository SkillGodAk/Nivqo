/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import java.io.File

/** Local file header of a zip archive, the container both patch bundles and APKs use. */
private val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * Whether the file starts with a zip local file header.
 *
 * A download can succeed and still hand back the wrong thing: an API serving metadata instead of
 * the asset, or a captive portal answering with HTML. Both look like a healthy transfer, so the
 * archive header is what separates a real download from a plausible-looking one.
 */
fun File.hasZipHeader(): Boolean = runCatching {
    inputStream().use { input ->
        val header = ByteArray(ZIP_HEADER.size)
        input.read(header) == header.size && header.contentEquals(ZIP_HEADER)
    }
}.getOrDefault(false)
