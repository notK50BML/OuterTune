package com.dd3boh.betterlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** QQ's QRC, as BetterLyrics relays it, read back through the TTML parser both apps use. */
class QrcConverterTest {
    // Shaped like a real response: an XML wrapper, header tags, a credits block, then the song.
    private val qrc = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1" LyricContent="[ti:Shape of You]
[ar:Ed Sheeran]
[0,2380]Shape(0,476) (476,95)of(571,190) (761,95)You(856,286) (1142,95)-(1237,95) (1332,96)Ed(1428,190) (1618,95)Sheeran(1713,667)
[2380,2390]Lyrics(2380,159) (2539,159)by(2698,159)：(2857,159)Ed(3016,159)
[9000,1500]The(9000,300) (9300,100)club(9400,500) (9900,100)isn&apos;t(10000,500)
[11000,1200]the(11000,300) (11300,100)best(11400,800)
"/>
</LyricInfo>
</QrcInfos>"""

    @Test
    fun `the song's lines come through word by word, credits left out`() {
        val ttml = QrcConverter.toTtml(qrc, title = "Shape of You")!!
        val lines = TTMLParser.parseTTML(ttml)
        assertEquals(listOf("The club isn't", "the best"), lines.map { it.text })
        assertEquals(9.0, lines[0].startTime, 0.001)
        assertEquals(listOf("The", "club", "isn't"), lines[0].words.map { it.text })
        assertEquals(9.4, lines[0].words[1].startTime, 0.001)
        assertEquals(9.9, lines[0].words[1].endTime, 0.001)
    }

    @Test
    fun `a raw quote inside the lyrics does not cut them short`() {
        // As QQ sends Blinding Lights: quotes unescaped in the credits, inside the attribute.
        val quoted = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1" LyricContent="[ti:Blinding Lights]
[1757,4829]Lyrics (1757,439)by：(2196,439)Abel "(3513,439)The (3952,439)Weeknd" (4391,439)Tesfaye(4830,439)
[27672,2784]I've (27672,200)been (27872,144)tryna (28016,176)call(28192,632)
[30456,2720]I said "(30456,184)hello"(30640,177)
"/>
</LyricInfo>
</QrcInfos>"""
        val lines = TTMLParser.parseTTML(QrcConverter.toTtml(quoted, title = "Blinding Lights")!!)
        assertEquals(listOf("I've been tryna call", "I said \"hello\""), lines.map { it.text.trim() })
        assertEquals(4, lines[0].words.size)
    }

    @Test
    fun `nothing timed is no lyrics`() {
        assertNull(QrcConverter.toTtml("""<QrcInfos><Lyric_1 LyricContent="[ti:x]"/></QrcInfos>"""))
    }
}
