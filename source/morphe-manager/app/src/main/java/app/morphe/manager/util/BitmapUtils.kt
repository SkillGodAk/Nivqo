/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * The smallest power of two that brings both sides to [maxDimension] or under, which is the only
 * kind of sampling [BitmapFactory] applies exactly.
 */
fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * The largest power of two that keeps both sides at [minDimension] or over, for a thumbnail that
 * is as small as it can be while still filling its slot.
 */
fun thumbnailSampleSize(width: Int, height: Int, minDimension: Int): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= minDimension && height / (sampleSize * 2) >= minDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Decodes the image at [uri] no larger than [maxDimension] on a side. A photo straight off a
 * camera runs to 100 MB or more decoded, which is more than the heap allows on many devices.
 */
fun ContentResolver.decodeSampledBitmap(uri: Uri, maxDimension: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // A bounds-only decode always returns null, so only a missing stream or unreadable size fail here
    (openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    return openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}
