package com.dd3boh.betterlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TTMLParser.toLrc]'s two modes: plain LRC, unchanged from before enhanced output existed, and
 * Enhanced LRC, which carries the word timings this parser already extracts.
 *
 * The invariant that matters most is that the two modes describe the *same words in the same
 * order*: stripping every `<mm:ss.cc>` mark from the enhanced output has to give back the plain
 * output. That is what lets a line-level reader treat the enhanced form as ordinary LRC.
 */
class TTMLParserTest {

    private val wordMark = Regex("<\\d+:\\d{2}[.:]\\d+>")

    private fun ttml(body: String) = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body><div>$body</div></body>
        </tt>
    """.trimIndent()

    private val syllableLine = ttml(
        """<p begin="00:21.100" end="00:23.200">""" +
            """<span begin="00:21.100" end="00:21.620">Never </span>""" +
            """<span begin="00:21.620" end="00:22.050">gonna </span>""" +
            """<span begin="00:22.050" end="00:22.480">give </span>""" +
            """<span begin="00:22.480" end="00:22.790">you </span>""" +
            """<span begin="00:22.790" end="00:23.200">up</span></p>"""
    )

    @Test
    fun plainMode_isUnchangedByTheEnhancedFeature() {
        assertEquals(
            "[00:21.10]Never gonna give you up",
            TTMLParser.toLrc(TTMLParser.parseTTML(syllableLine), enhanced = false)
        )
    }

    @Test
    fun enhancedMode_emitsAWordMarkPerWordAndAnEndMark() {
        assertEquals(
            "[00:21.10]<00:21.10>Never <00:21.62>gonna <00:22.05>give <00:22.48>you <00:22.79>up<00:23.20>",
            TTMLParser.toLrc(TTMLParser.parseTTML(syllableLine), enhanced = true)
        )
    }

    @Test
    fun enhancedMode_strippedOfWordMarks_equalsPlainMode() {
        val lines = TTMLParser.parseTTML(syllableLine)
        assertEquals(
            TTMLParser.toLrc(lines, enhanced = false),
            TTMLParser.toLrc(lines, enhanced = true).replace(wordMark, "")
        )
    }

    /**
     * Real documents mix the two: some lines carry syllable spans, some are bare text. Each line has
     * to degrade on its own rather than the whole document dropping to line level.
     */
    @Test
    fun mixedDocument_keepsWordMarksOnlyWhereThereAreWords() {
        val lrc = TTMLParser.ttmlToLrc(
            ttml(
                """<p begin="00:21.100" end="00:23.200">""" +
                    """<span begin="00:21.100" end="00:21.620">Never </span>""" +
                    """<span begin="00:21.620" end="00:22.050">gonna</span></p>""" +
                    """<p begin="00:25.000" end="00:27.000">a bare line</p>"""
            )
        )!!.lines()

        assertEquals(2, lrc.size)
        assertEquals("[00:21.10]<00:21.10>Never <00:21.62>gonna<00:22.05>", lrc[0])
        assertEquals("[00:25.00]a bare line", lrc[1])
    }

    /**
     * A word containing a character the LRC syntax gives meaning to would be read back as a tag and
     * corrupt the whole line, not merely lose its timing. Such a line stays line-level.
     */
    @Test
    fun wordWithReservedCharacter_fallsBackToLineLevel() {
        val lrc = TTMLParser.ttmlToLrc(
            ttml("""<p begin="00:40.000" end="00:41.000"><span begin="00:40.000" end="00:41.000">a&lt;b</span></p>""")
        )!!
        assertFalse("no word mark should have been emitted", wordMark.containsMatchIn(lrc))
        assertEquals("[00:40.00]a<b", lrc)
    }

    /**
     * The end mark is what pins the last word's end time; without it a reader has to estimate it.
     * It is pointless, though, when it rounds to the same centisecond as the word's own start.
     */
    @Test
    fun endMarkIsOmittedWhenItWouldRepeatTheLastWordsStart() {
        val lrc = TTMLParser.ttmlToLrc(
            ttml("""<p begin="00:10.000"><span begin="00:10.000" end="00:10.003">hi</span></p>""")
        )!!
        assertEquals("[00:10.00]<00:10.00>hi", lrc)
    }

    @Test
    fun backgroundVocalsGetTheirOwnEnhancedLine() {
        val lrc = TTMLParser.ttmlToLrc(
            ttml(
                """<p begin="00:30.000">""" +
                    """<span begin="00:30.000" end="00:30.400">hey</span>""" +
                    """<span ttm:role="x-bg" begin="00:30.200" end="00:31.000">""" +
                    """<span begin="00:30.200" end="00:31.000">(ooh)</span></span></p>"""
            )
        )!!.lines()

        assertEquals(2, lrc.size)
        assertTrue(lrc[0].startsWith("[00:30.00]<00:30.00>hey"))
        assertTrue(lrc[1].startsWith("[00:30.20]<00:30.20>(ooh)"))
    }

    @Test
    fun ttmlToLrc_isEnhancedByDefault() {
        assertTrue(TTMLParser.ttmlToLrc(syllableLine)!!.contains("<00:21.62>"))
    }

    @Test
    fun nonTtmlInput_yieldsNull() {
        assertEquals(null, TTMLParser.ttmlToLrc("[00:01.00]not ttml at all"))
    }
}
