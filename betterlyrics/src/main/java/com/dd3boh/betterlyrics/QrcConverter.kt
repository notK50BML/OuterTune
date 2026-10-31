package com.dd3boh.betterlyrics

/**
 * QQ Music's word-timed QRC, turned into the TTML the rest of the lyrics code already reads - so a
 * QQ result is drawn word by word exactly as a BetterLyrics one is, on both apps, with no parser of
 * its own downstream.
 *
 * QRC is an XML wrapper whose `LyricContent` attribute holds lines like
 * `[2380,2390]Lyrics(2380,159) (2539,159)by(2698,159)`: the line's start and length, then each word
 * followed by its own start and length, all in milliseconds from the start of the song. Spaces come
 * as words of their own.
 */
object QrcConverter {
    private val CONTENT = Regex("""LyricContent="([^"]*)"""")
    private val LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD = Regex("""(.*?)\((\d+),(\d+)\)""")

    // QQ opens most songs with a credits block - the title and artist, then "Lyrics by：…",
    // "Composed by：…" - timed as if they were sung. Shown, they read as the song's first lines.
    private val CREDIT = Regex("""(?i)^\s*(lyrics|composed|composer|produced|producer|written|writer|arranged|arranger|mixed|mastered|music|words|vocals?)\b.*[:：]""")

    /** The TTML for [qrc], or null when it holds no timed lines. */
    fun toTtml(qrc: String, title: String? = null): String? {
        val content = CONTENT.find(qrc)?.groupValues?.get(1)?.let(::unescape) ?: qrc
        val paragraphs = StringBuilder()
        var lines = 0
        for (raw in content.lines()) {
            val match = LINE.find(raw.trim()) ?: continue
            val start = match.groupValues[1].toLong()
            val length = match.groupValues[2].toLong()
            val words = WORD.findAll(match.groupValues[3]).map { Triple(it.groupValues[1], it.groupValues[2].toLong(), it.groupValues[3].toLong()) }.toList()
            val text = words.joinToString("") { it.first }.trim()
            if (text.isEmpty() || isCredit(text, title)) continue
            paragraphs.append("<p begin=\"").append(seconds(start)).append("\" end=\"").append(seconds(start + length)).append("\">")
            for ((word, wordStart, wordLength) in words) {
                if (word.isBlank()) {
                    paragraphs.append(' ')
                } else {
                    paragraphs.append("<span begin=\"").append(seconds(wordStart)).append("\" end=\"")
                        .append(seconds(wordStart + wordLength)).append("\">").append(escape(word)).append("</span>")
                }
            }
            paragraphs.append("</p>")
            lines++
        }
        if (lines == 0) return null
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>$paragraphs</div></body></tt>"
    }

    private fun isCredit(text: String, title: String?): Boolean =
        CREDIT.containsMatchIn(text) || text.contains('：') ||
            (title != null && text.startsWith(title, ignoreCase = true) && text.contains(" - "))

    private fun seconds(ms: Long) = "%d.%03d".format(ms / 1000, ms % 1000)

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&")

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
