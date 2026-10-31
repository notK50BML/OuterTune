package com.dd3boh.betterlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sings what: the voices the shared TTML reader gives a duet's lines, the names it finds for the
 * legend, the "Name: " prefixes it will and will not treat as a singer, and the colours each voice
 * is drawn in.
 */
class SingersTest {

    /** Apple's shape: agents declared in the head, two named, and a group; lines pointing at them. */
    private val appleDuet = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <head><metadata>
            <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Jung Kook</ttm:name></ttm:agent>
            <ttm:agent type="person" xml:id="v2"><ttm:name type="full">Latto</ttm:name></ttm:agent>
            <ttm:agent type="group" xml:id="v1000"/>
          </metadata></head>
          <body><div>
            <p begin="1.0" end="2.0" ttm:agent="v1"><span begin="1.0" end="1.5">Night </span><span begin="1.5" end="2.0">and</span><span ttm:role="x-bg"><span begin="1.6" end="2.0">(ooh)</span></span></p>
            <p begin="3.0" end="4.0" ttm:agent="v2">Uh, yeah</p>
            <p begin="5.0" end="6.0" ttm:agent="v1000">Seven days a week</p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `an Apple head gives each line its singer's voice, background vocals included`() {
        val parsed = TTMLParser.parse(appleDuet)
        assertEquals(listOf(Voice.ONE, Voice.TWO, Voice.GROUP), parsed.lines.map { it.voice })
        assertEquals(Voice.ONE, parsed.lines[0].backgroundLines.single().voice)
        assertEquals(listOf(Singer(Voice.ONE, "Jung Kook"), Singer(Voice.TWO, "Latto")), parsed.singers)
        assertEquals(listOf(Voice.ONE to "Jung Kook", Voice.TWO to "Latto"), Singers.legend(parsed.singers))
    }

    @Test
    fun `a trio's third singer shares the first voice, as on the phone`() {
        val voices = Singers.voices(
            listOf(
                Singers.Agent("v1", "person", "A"),
                Singers.Agent("v2", "person", "B"),
                Singers.Agent("v3", "person", "C"),
            )
        )
        assertEquals(mapOf("v1" to Voice.ONE, "v2" to Voice.TWO, "v3" to Voice.ONE), voices)
        assertEquals(
            listOf(Voice.ONE to "A & C", Voice.TWO to "B"),
            Singers.legend(listOf(Singer(Voice.ONE, "A"), Singer(Voice.TWO, "B"), Singer(Voice.ONE, "C"))),
        )
    }

    @Test
    fun `the LRC fallback keeps the voices as singer marks`() {
        val lrc = TTMLParser.ttmlToLrc(appleDuet)!!.lines()
        assertTrue(lrc[0], lrc[0].startsWith("[00:01.00]v1:<00:01.00>Night"))
        assertEquals("[00:01.00]v1:<00:01.60>(ooh)<00:02.00>", lrc[1])
        assertEquals("[00:03.00]v2:Uh, yeah", lrc[2])
        assertEquals("[00:05.00]v3:Seven days a week", lrc[3])
    }

    @Test
    fun `one voice only gets no singer marks at all`() {
        val solo = appleDuet.replace("ttm:agent=\"v2\"", "ttm:agent=\"v1\"").replace("ttm:agent=\"v1000\"", "ttm:agent=\"v1\"")
        assertTrue(TTMLParser.ttmlToLrc(solo)!!.lines().none { it.contains("v1:") })
    }

    /** QQ's way: no agents, the singer written at the start of the line, sometimes once a verse. */
    private val namedInText = """
        <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
          <p begin="1.0" end="2.0">Jung Kook: Night and day</p>
          <p begin="3.0" end="4.0">Seven days a week</p>
          <p begin="5.0" end="6.0">Latto: Uh, yeah</p>
          <p begin="7.0" end="8.0">Latto: Tell me</p>
          <p begin="9.0" end="10.0">All: Seven days</p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `names written into the lines become the singers, and the text is left alone`() {
        val parsed = TTMLParser.parse(namedInText)
        assertEquals(
            listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.TWO, Voice.GROUP),
            parsed.lines.map { it.voice },
        )
        assertEquals("Jung Kook: Night and day", parsed.lines[0].text)
        assertEquals(listOf("Jung Kook", "Latto", "All"), parsed.singers.map { it.name })
    }

    @Test
    fun `names in plain lines give the same voices and singers as in TTML`() {
        // LRC has nowhere to keep agents, so KuGou's, LRCLIB's and QQ's line-timed lyrics get theirs
        // from the names alone - and must come out exactly as the TTML reader reads the same lines.
        val texts = listOf("Jung Kook: Night and day", "Seven days a week", "Latto: Uh, yeah", "Latto:", "All: Seven")
        val (voices, singers) = Singers.voicesFromNames(texts)!!
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.TWO, Voice.GROUP), voices)
        assertEquals(listOf(Singer(Voice.ONE, "Jung Kook"), Singer(Voice.TWO, "Latto"), Singer(Voice.GROUP, "All")), singers)
        assertNull(Singers.voicesFromNames(listOf("Night and day", "Remember: you're mine")))
    }

    @Test
    fun `a name prefix is cut exactly, full-width colon included`() {
        val names = Singers.namePrefixes(listOf("男：我爱你", "女：我也是", "男：真的吗"))
        assertEquals(setOf("男", "女"), names)
        assertEquals(2, Singers.namePrefixLength("男：我爱你", names))
        assertEquals("Uh, yeah", "Latto: Uh, yeah".drop(Singers.namePrefixLength("Latto: Uh, yeah", setOf("Latto"))))
        assertEquals(0, Singers.namePrefixLength("Night and day", setOf("Latto")))
    }

    @Test
    fun `ordinary lyrics with a colon are not taken for names`() {
        // One name-shaped prefix, however often it repeats word for word, is a lyric.
        assertEquals(emptySet<String>(), Singers.namePrefixes(listOf("Mama: I just killed a man", "Something else")))
        assertEquals(emptySet<String>(), Singers.namePrefixes(List(4) { "Remember: you're mine" }))
        // Sentences, section labels, credits and times are not names.
        for (line in listOf("Listen to me: now", "Verse 2: here", "Lyrics By: Ed", "It's 3:45 now", "Chorus: la la", "so baby: please")) {
            assertNull(line, Singers.leadingName(line))
        }
        assertEquals(
            emptySet<String>(),
            Singers.namePrefixes(listOf("Verse 1: one", "Chorus: two", "Verse 1: three", "Chorus: four")),
        )
    }

    @Test
    fun `QQ lines labelled with a full-width colon are kept rather than dropped as credits`() {
        val qrc = """<QrcInfos><Lyric_1 LyricContent="[0,1000]作词(0,500)：(500,500)某人(1000,0)
[1000,1000]男(1000,100)：(1100,100)我(1200,400)爱(1600,400)
[2000,1000]女(2000,100)：(2100,100)我(2200,400)也(2600,400)
[3000,1000]男(3000,100)：(3100,100)真(3200,400)的(3600,400)
"/></QrcInfos>"""
        val parsed = TTMLParser.parse(QrcConverter.toTtml(qrc)!!)
        assertEquals(listOf("男：我爱", "女：我也", "男：真的"), parsed.lines.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.TWO, Voice.ONE), parsed.lines.map { it.voice })
    }

    @Test
    fun `voice colours stay readable on dark and light backgrounds and apart from each other`() {
        // A Material-ish blue and pink, as dark and light themes might offer them.
        val primary = 0xFF3F51B5.toInt()
        val tertiary = 0xFFC2185B.toInt()
        for ((on, background) in listOf(0xFFFFFFFF.toInt() to 0xFF121212.toInt(), 0xFF000000.toInt() to 0xFFFAFAFA.toInt())) {
            val colours = Voice.entries.map { SingerColours.forVoice(it, primary, tertiary, on) }
            for (colour in colours) {
                assertTrue("%08x on %08x".format(colour, background), SingerColours.contrast(colour, background) >= 4.5)
            }
            assertEquals(3, colours.distinct().size)
        }
        // Two accents of the same hue are turned apart rather than drawn as one colour.
        val (a, b) = SingerColours.distinctPair(0xFF3F51B5.toInt(), 0xFF5C6BC0.toInt())
        assertNotEquals(SingerColours.forVoice(Voice.ONE, a, b, -1), SingerColours.forVoice(Voice.TWO, a, b, -1))
    }
}
