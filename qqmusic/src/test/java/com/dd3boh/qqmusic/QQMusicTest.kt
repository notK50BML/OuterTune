package com.dd3boh.qqmusic

import com.dd3boh.betterlyrics.QrcConverter
import com.dd3boh.betterlyrics.TTMLParser
import com.dd3boh.qqmusic.models.SearchResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class QQMusicTest {
    // A small QRC document, zlib-compressed and encrypted by LDDC's own Python decoder (its
    // `tripledes_crypt` in ENCRYPT mode, round-tripped through its decrypt) - so this checks the port
    // against the reference, without keeping a real song's lyrics in the repo. Live payloads were
    // also checked byte for byte against the reference (see `live` below).
    private val encrypted =
        "69E3CDB6D8E1D506B758E27CEC7B9CCD26464AFC10C85BBE7C6DA9623DDE5D476E896BF72A9A6588E00EA99B21E3B44A" +
            "A903AC09EAD7C09F0A154997C1D99B26E84C0D75FB3DAC2B83782E4367CBF918F60912AD15E289940F538AC6EF3AAFA4" +
            "16D04F65CF35D42CBD5B6DE20D047C59C21DA8A8A69E968647FA446CF916A610BDA1085DD013562FDEF57B0CDBA8F346" +
            "B5AD8A7ACCB737EA4F5E8BC6AE155E74B8402DE6F86038060E05165EE8D17A4947FED5A20298CE9AC2381B87770F1A33" +
            "4EF7664023F70D3AEAF91A85FB7C48092568A4F510FCBB88FE290A1B7FB8F0F030498F96F260A22AAA7E678958465094" +
            "83B8B37A9114C7F0526A09ACF08E0E503EA3CC14C4E33459CEB6DA05FAB48735F1A6ACCC2D778B0B9C7E27A993140391" +
            "FCE3F2A8DC5C6860BB7061315DFE0B140666DF9B3EA51015A0FD5CCE13B6FB67"

    private val plain = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
<QrcHeadInfo SaveTime="1" Version="100"/>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1" LyricContent="[ti:Test Song]
[ar:Someone]
[0,2000]Test(0,500) (500,100)Song(600,400) (1000,100)-(1100,100) (1200,100)Someone(1300,700)
[2000,1500]Lyrics(2000,300) (2300,100)by(2400,300)：(2700,100)Someone(2800,700)
[5000,1800]Hello(5000,600) (5600,100)world(5700,700) (6400,100)again(6500,300)
[7000,1200]你(7000,400)好(7400,800)
"/>
</LyricInfo>
</QrcInfos>"""

    @Test
    fun `QQ's triple DES opens a payload made by the reference decoder`() {
        assertEquals(plain, QrcDecrypter.decrypt(encrypted))
        assertEquals(plain, QrcDecrypter.decrypt(encrypted.lowercase()))
    }

    @Test
    fun `standard DESede does not - QQ's DES is its own`() {
        val bytes = encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(), "DESede"))
        val first = cipher.doFinal(bytes.copyOf(8))
        // zlib's header would be 0x78; the real decoder gets it, the standard one does not.
        assertTrue(first[0] != 0x78.toByte())
    }

    @Test
    fun `a broken payload is an error, not lyrics`() {
        assertTrue(runCatching { QrcDecrypter.decrypt(encrypted.dropLast(2)) }.isFailure)
        assertTrue(runCatching { QrcDecrypter.decrypt(encrypted.take(64)) }.isFailure)
        assertTrue(runCatching { QrcDecrypter.decrypt("") }.isFailure)
    }

    @Test
    fun `the decrypted QRC becomes word-timed TTML with the credits left out`() {
        val ttml = QrcConverter.toTtml(QrcDecrypter.decrypt(encrypted), "Test Song")!!
        val lines = TTMLParser.parseTTML(ttml)
        assertEquals(listOf("Hello world again", "你好"), lines.map { it.text })
        assertEquals(listOf("Hello", "world", "again"), lines[0].words.map { it.text })
        assertEquals(5.7, lines[0].words[1].startTime, 0.001)
        assertEquals(6.4, lines[0].words[1].endTime, 0.001)
    }

    @Test
    fun `QQ's own title line is dropped even when it is spaced differently from the track's title`() {
        val qrc = """<Lyric_1 LyricType="1" LyricContent="[ti:等你下课]
[0,3000]等你下课(with(0,300) (300,100)杨瑞代)(400,500) (900,100)-(1000,100)周杰伦(1100,900)
[15160,3000]Jay：(15160,500)你住的(15660,800) (16460,100)巷子里(16560,900)
[18770,3000]我租了一间公寓(18770,3000)
[40000,3000]Gary：(40000,500)学校旁(40500,800) (41300,100)的广场(41400,900)
[44000,3000]Jay：(44000,500)弹着琴(44500,800) (45300,100)唱你爱的歌(45400,900)
"/>"""
        val ttml = QrcConverter.toTtml(QQMusic.withoutTitleLine(qrc, listOf("等你下课", "等你下课 (with 杨瑞代)")), "等你下课")!!
        assertEquals(
            listOf("Jay：你住的 巷子里", "我租了一间公寓", "Gary：学校旁 的广场", "Jay：弹着琴 唱你爱的歌"),
            TTMLParser.parseTTML(ttml).map { it.text },
        )
        // A first line that is just the song's opening words stays.
        val opening = "[0,1000]Seven(0,500) (500,100)days(600,400)\n"
        assertEquals(opening, QQMusic.withoutTitleLine(opening, listOf("Seven")))
    }

    private fun song(id: Long, title: String, duration: Int, vararg singers: String, name: String = title) =
        SearchResponse.Song(
            id = id, mid = "mid$id", name = name, title = title, duration = duration,
            singer = singers.map { SearchResponse.Singer(it) },
        )

    // Shaped like QQ's real answer for "Shape of You Ed Sheeran".
    private val shapeOfYou = listOf(
        song(200369396, "Shape of You", 233, "Ed Sheeran"),
        song(238194620, "Shape Of You (Elephante Remix)", 201, "Elephante", "Ed Sheeran", name = "Shape Of You"),
        song(202773247, "Shape of You (Monjigo Remix)", 187, "Monjigo", "Ed Sheeran", name = "Shape of You"),
        song(272234474, "Shape of You (现场版)", 284, "Ed Sheeran", name = "Shape of You"),
        song(1, "Shape of You", 234, "Some Cover Band"),
    )

    @Test
    fun `the recording of the right length wins`() {
        val ranked = QQMusic.rankCandidates(shapeOfYou, "Shape of You", "Ed Sheeran", 233, strict = true)
        assertEquals(listOf(200369396L, 1L), ranked.map { it.id })
    }

    @Test
    fun `with no length known the original comes before its remixes`() {
        val ranked = QQMusic.rankCandidates(shapeOfYou, "Shape of You", "Ed Sheeran", -1, strict = true)
        assertEquals(200369396L, ranked.first().id)
        assertEquals(1L, ranked.last().id) // a different artist's song of the same name, last
    }

    @Test
    fun `nothing near the length is no match`() {
        assertTrue(QQMusic.rankCandidates(shapeOfYou, "Shape of You", "Ed Sheeran", 300, strict = true).isEmpty())
    }

    @Test
    fun `another song by the same artist is not taken without the length to back it`() {
        val songs = listOf(song(5, "Perfect", 263, "Ed Sheeran"))
        assertTrue(QQMusic.rankCandidates(songs, "Shape of You", "Ed Sheeran", -1, strict = true).isEmpty())
        // ...but a title in another script, by the right artist at the right length, is.
        val traditional = listOf(song(6, "等你下课 (with 杨瑞代)", 270, "周杰伦"))
        assertEquals(1, QQMusic.rankCandidates(traditional, "等你下課", "周杰伦", 270, strict = true).size)
        // A manual search offers anything of the right length.
        assertEquals(1, QQMusic.rankCandidates(songs, "Shape of You", "Ed Sheeran", 262, strict = false).size)
    }

    @Test
    fun `titles match without their bracketed extras and version suffixes`() {
        assertTrue(QQMusic.titleMatches("Seven", "Seven (feat. Latto) - Explicit Ver."))
        assertTrue(QQMusic.titleMatches("Seven (feat. Latto) (Clean Ver.)", "Seven (feat. Latto) - Clean Ver."))
        assertTrue(QQMusic.titleMatches("等你下课", "等你下课 (with 杨瑞代)"))
        assertTrue(QQMusic.titleMatches("SHAPE OF YOU", "Shape of You"))
        assertTrue(QQMusic.titleMatches("Seven feat. Latto", "Seven"))
        assertFalse(QQMusic.titleMatches("Seven", "Seven Nation Army"))
        assertFalse(QQMusic.titleMatches("Seven", "Seventeen Again"))
        assertFalse(QQMusic.titleMatches("Golden", "Golden Hour"))
    }

    @Test
    fun `artists match on any one name`() {
        assertTrue(QQMusic.artistMatches("Jung Kook feat. Latto", listOf("Jung Kook", "Latto")))
        assertTrue(QQMusic.artistMatches("Jung Kook, Latto", listOf("Latto")))
        assertTrue(QQMusic.artistMatches("周杰倫 & 楊瑞代", listOf("Vk", "楊瑞代")))
        assertTrue(QQMusic.artistMatches("HUNTR/X", listOf("HUNTR/X")))
        assertFalse(QQMusic.artistMatches("Ed Sheeran", listOf("Some Cover Band")))
    }

    /**
     * Asks QQ Music for real - only when `QQ_LIVE` is set, so the normal build stays offline:
     * `QQ_LIVE=1 ./gradlew :qqmusic:test --tests '*QQMusicTest.live'`
     */
    @Test
    fun live() = runBlocking {
        assumeTrue(System.getenv("QQ_LIVE") != null)
        // The lyric endpoints on their own, by ids an earlier search returned - so they can be checked
        // even while QQ is turning searches away (code 2001).
        val known = listOf(
            song(200369396, "Shape of You", 233, "Ed Sheeran").copy(mid = "0041gObR2QG98x"),
            song(421328954, "Seven (feat. Latto) - Explicit Ver.", 184, "Jung Kook", "Latto").copy(mid = "000lgImI2KHe6L"),
            song(212877900, "等你下课 (with 杨瑞代)", 270, "周杰伦").copy(mid = "001J5QJL1pRQYB"),
        )
        for (song in known) report("by id ${song.id} ${song.title}", runCatching { QQMusic.lyricsFor(song, song.name) })

        val songs = listOf(
            Triple("Shape of You", "Ed Sheeran", 233),
            Triple("Seven (feat. Latto)", "Jung Kook, Latto", 184),
            Triple("等你下课", "周杰伦", 270),
            Triple("Golden", "HUNTR/X, EJAE, AUDREY NUNA, REI AMI, KPop Demon Hunters Cast", 194),
        )
        for ((title, artist, duration) in songs) {
            report("search $title / $artist ($duration s)", QQMusic.getLyrics(title, artist, duration))
        }
        println("==== no such song: ${QQMusic.getLyrics("zzqqxx no such song", "nobody at all", 200)}")
    }

    private fun report(label: String, result: Result<String?>) {
        println("==== $label: ${if (result.isSuccess) "success" else "FAILURE ${result.exceptionOrNull()}"}")
        val text = result.getOrNull() ?: return println("  (no lyrics)")
        if (TTMLParser.looksLikeTtml(text)) {
            val parsed = TTMLParser.parse(text)
            val lines = parsed.lines
            println("  TTML: ${lines.size} lines, ${lines.sumOf { it.words.size }} word spans, singers=${parsed.singers}")
            lines.take(6).forEach { println("  [%.2f] %s  (voice=%s, words=%d)".format(it.startTime, it.text, it.voice, it.words.size)) }
            lines.filter { Regex("""^[^\s:：]{1,20}[:：]""").containsMatchIn(it.text) }.take(4)
                .forEach { println("  name-prefixed: ${it.text}") }
        } else {
            println("  LRC:")
            text.lines().take(8).forEach { println("  $it") }
        }
    }
}
