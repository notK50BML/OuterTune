package com.dd3boh.outertune.ui.utils

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
