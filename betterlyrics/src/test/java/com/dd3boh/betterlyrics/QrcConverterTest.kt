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
    fun `nothing timed is no lyrics`() {
        assertNull(QrcConverter.toTtml("""<QrcInfos><Lyric_1 LyricContent="[ti:x]"/></QrcInfos>"""))
    }
}
