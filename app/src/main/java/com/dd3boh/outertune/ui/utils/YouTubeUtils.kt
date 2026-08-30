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

