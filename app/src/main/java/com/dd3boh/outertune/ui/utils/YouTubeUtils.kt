package com.dd3boh.outertune.ui.utils

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Compiled once rather than on every call. [resize] is now invoked from list item composables,
 * so it runs on every artwork bind while scrolling; rebuilding these patterns each time was
 * enough overhead to show up as jank in long lists.
 */
private val GOOGLE_USERCONTENT_SIZED =
    Regex("""https://[a-z0-9]+\.googleusercontent\.com/.*=w(\d+)-h(\d+).*""")
private val YT3_GGPHT_SIZED = Regex("""https://yt3\.ggpht\.com/.*=s(\d+)""")

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    GOOGLE_USERCONTENT_SIZED.matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        // Multiply before dividing - the reverse order truncated to zero whenever the
        // requested dimension was smaller than the source's own, and was wrong by a rounding
        // error otherwise (e.g. w=1200 against a 544-wide source produced 1088, not the
        // proportional 1200, because 1200/544 truncated to 2 before the *544). Dead until
        // naturalAspectRatioOrNull()'s caller below, since every other call site here passes
        // both dimensions.
        if (w != null && h == null) h = (w.toLong() * H / W).toInt()
        if (w == null && h != null) w = (h.toLong() * W / H).toInt()
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches YT3_GGPHT_SIZED) {
        return "$this-s${width ?: height}"
    }
    if (this.contains("i.ytimg.com")) {
        val w = width ?: height!!
        return if (w > 480) {
            this.replace("hqdefault.jpg", "maxresdefault.jpg")
                .replace("mqdefault.jpg", "maxresdefault.jpg")
                .replace("sddefault.jpg", "maxresdefault.jpg")
        } else if (w > 320) {
            this.replace("mqdefault.jpg", "hqdefault.jpg")
        } else {
            this
        }
    }
    return this
}

/**
 * This image's own aspect ratio (width / height), read from the size its CDN URL already embeds -
 * null when the scheme gives no such hint (i.ytimg.com's fixed-name thumbnails, mainly).
 *
 * Every existing caller of [resize] asks for a fixed width *and* height - a square thumbnail, or
 * a size matching a fixed-shape layout slot - which is right for uniformly-shaped artwork like
 * album covers, but forces a crop on artist images: some are old square channel avatars, some are
 * newer wide banner-style photos, and asking for a fixed shape regardless discards whichever
 * dimension that shape didn't ask for. A caller that wants to lay out around the source's real
 * shape instead of cropping it needs to know that shape before it requests any size at all.
 */
fun String.naturalAspectRatioOrNull(): Float? {
    GOOGLE_USERCONTENT_SIZED.matchEntire(this)?.groupValues?.let { group ->
        val (w, h) = group.drop(1).map { it.toInt() }
        if (w > 0 && h > 0) return w.toFloat() / h
    }
    // This scheme (yt3.ggpht.com's =sSIZE) has no separate width/height parameter - it only ever
    // serves a square crop, which is exactly the "old square avatar" case.
    if (this matches YT3_GGPHT_SIZED) return 1f
    return null
}

/** Below this relative luminance a sampled pixel counts as "black bar", not just a dark scene. */
private const val LETTERBOX_LUMINANCE_THRESHOLD = 0.06f

/** How much of a sampled row/column has to clear that threshold for the whole row/column to
 *  count as bar - not 100%, so a stray bright pixel (noise, a faint watermark) doesn't stop the
 *  scan one row early. */
private const val LETTERBOX_ROW_DARK_FRACTION = 0.96f

/** Never trim more than this fraction of a dimension from a single edge - a real photo with a
 *  dark border, or one that's just genuinely dark near an edge, shouldn't be mistaken for an
 *  almost-entirely-letterboxed image. */
private const val LETTERBOX_MAX_TRIM_FRACTION = 0.4f

/** Below this, on both axes, there's nothing worth cropping for - a pixel or two of compression
 *  ringing at the edge shouldn't trigger a crop and a different layout. */
private const val LETTERBOX_MIN_TRIM_FRACTION = 0.03f

/**
 * The sub-rectangle of [this] bitmap that excludes solid near-black bars along its edges - the
 * letterboxing/pillarboxing some artist photos have baked directly into their pixels (a
 * non-square photo centred on a square canvas with the gaps filled in black, say), which no CDN
 * resize parameter can see or remove because it isn't a shape the *file* has, only the *picture*
 * within it does.
 *
 * Returns null when there is nothing worth trimming: no bar was found on any edge, or what was
 * found doesn't clear [LETTERBOX_MIN_TRIM_FRACTION] on either axis. Each edge is capped at
 * [LETTERBOX_MAX_TRIM_FRACTION] of its dimension, so a photo that's simply dark near one edge
 * can't be mistaken for one that's almost entirely bars.
 */
fun Bitmap.detectLetterboxContentBounds(): Rect? {
    val w = width
    val h = height
    if (w <= 1 || h <= 1) return null

    fun luminanceAt(x: Int, y: Int): Float {
        val pixel = getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    // Sampled, not scanned pixel-by-pixel along the row/column - plenty of resolution to tell a
    // deliberate bar from a busy photo without costing more than a handful of pixel reads per
    // row/column checked.
    val sampleCols = minOf(w, 32)
    val sampleRows = minOf(h, 32)

    fun rowIsBar(y: Int): Boolean {
        var dark = 0
        for (i in 0 until sampleCols) {
            val x = (i * (w - 1)) / (sampleCols - 1).coerceAtLeast(1)
            if (luminanceAt(x, y) < LETTERBOX_LUMINANCE_THRESHOLD) dark++
        }
        return dark >= sampleCols * LETTERBOX_ROW_DARK_FRACTION
    }

    fun colIsBar(x: Int): Boolean {
        var dark = 0
        for (i in 0 until sampleRows) {
            val y = (i * (h - 1)) / (sampleRows - 1).coerceAtLeast(1)
            if (luminanceAt(x, y) < LETTERBOX_LUMINANCE_THRESHOLD) dark++
        }
        return dark >= sampleRows * LETTERBOX_ROW_DARK_FRACTION
    }

    val maxVerticalTrim = (h * LETTERBOX_MAX_TRIM_FRACTION).toInt()
    val maxHorizontalTrim = (w * LETTERBOX_MAX_TRIM_FRACTION).toInt()

    var top = 0
    while (top < maxVerticalTrim && rowIsBar(top)) top++
    var bottom = h - 1
    while (bottom > (h - 1 - maxVerticalTrim) && rowIsBar(bottom)) bottom--
    var left = 0
    while (left < maxHorizontalTrim && colIsBar(left)) left++
    var right = w - 1
    while (right > (w - 1 - maxHorizontalTrim) && colIsBar(right)) right--

    if (top == 0 && bottom == h - 1 && left == 0 && right == w - 1) return null

    val trimmedVerticalFraction = (top + (h - 1 - bottom)).toFloat() / h
    val trimmedHorizontalFraction = (left + (w - 1 - right)).toFloat() / w
    if (trimmedVerticalFraction < LETTERBOX_MIN_TRIM_FRACTION &&
        trimmedHorizontalFraction < LETTERBOX_MIN_TRIM_FRACTION
    ) {
        return null
    }

    if (right <= left || bottom <= top) return null
    // Rect's right/bottom are exclusive; top/left/right/bottom above are the last bar-free row/
    // column index on each side, inclusive.
    return Rect(left, top, right + 1, bottom + 1)
}
