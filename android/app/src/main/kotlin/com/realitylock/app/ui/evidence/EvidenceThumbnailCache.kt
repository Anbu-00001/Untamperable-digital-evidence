package com.realitylock.app.ui.evidence

import android.graphics.Bitmap
import android.util.LruCache

/**
 * A process-wide LRU cache of decoded thumbnails, keyed by media path and the
 * pixel size it was decoded for.
 *
 * ## Why a cache is load-bearing here, not an optimisation
 *
 * The History tab is a `LazyColumn`, which destroys a row's composition the
 * moment it leaves the viewport and rebuilds it on the way back. Without a
 * cache, every scroll reversal re-runs a JPEG decode per visible row. Even a
 * downsampled decode is tens of milliseconds; three or four of them landing
 * together is a visible stutter, and it repeats on every scroll rather than
 * settling down.
 *
 * With the cache, a row that has been seen before finds its bitmap with a hash
 * lookup during composition and draws in the same frame — no coroutine, no
 * placeholder flash.
 *
 * ## Why nothing is ever `recycle()`d here
 *
 * The obvious move is to recycle on eviction, and it is a crash. Eviction is
 * driven by memory pressure, not by what is on screen: `LruCache` will happily
 * evict a bitmap that a composable is at that moment drawing, and drawing a
 * recycled bitmap throws `Canvas: trying to use a recycled bitmap`. Since
 * Android 8.0 bitmap pixels live on the native heap and are reclaimed by the GC
 * once unreferenced, so dropping the reference *is* the release — the only thing
 * `recycle()` would add is the ability to free memory that is still in use.
 *
 * The cache is therefore bounded by bytes, and the bound is what protects the
 * heap. (See `EvidenceImageDecoder.applyExifOrientation` for the one bitmap in
 * this package that *is* recycled, and why that case is different.)
 */
internal object EvidenceThumbnailCache {

    /**
     * Bounded by the bitmaps' actual byte count rather than by an entry count:
     * an entry count would let a handful of large thumbnails blow the budget
     * while a hundred small ones sat well under it.
     */
    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(maxSizeBytes()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    /**
     * Includes the pixel size, so a thumbnail decoded for a 72dp slot is never
     * served into a larger one as a blurry stand-in. Different densities and
     * different call sites get their own entries.
     */
    fun key(path: String, edgePx: Int): String = "$path@$edgePx"

    /**
     * Rejects a recycled bitmap defensively. Nothing in this package recycles a
     * cached bitmap, but returning one would crash at draw time, and a cache
     * that can hand out a landmine is worth one `if`.
     */
    fun get(key: String): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(key, bitmap)
    }

    /** Drops everything. For tests and for an explicit low-memory response. */
    fun clear() = cache.evictAll()

    /**
     * A fraction of the heap, clamped at both ends.
     *
     * The fraction alone is not enough: on a device with a small heap it yields
     * a cache too tiny to hold one screen of rows (so it never hits and the
     * stutter returns), and on a large-heap device it reserves far more than
     * thumbnails will ever need. The clamp keeps both ends sane.
     */
    private fun maxSizeBytes(): Int =
        (Runtime.getRuntime().maxMemory() / HEAP_FRACTION)
            .coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
            .toInt()

    private const val HEAP_FRACTION = 8L
    private const val MIN_CACHE_BYTES = 4L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
}
