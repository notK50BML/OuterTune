package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a caption track's own punctuation becomes when it is read as a lyric sheet.
 *
 * Auto-captions are a transcript. They carry speaker markers, bracketed sound annotations, notes
 * wrapped around every sung line, and the recogniser's own notes about what it could not hear -
 * all of which are information about the transcript rather than about the song.
 */
class CaptionAnnotationTest {

    private val note = "♪"

    // ---- speaker markers --------------------------------------------------------------------------

    @Test
    fun `a double angle marker is removed`() {
        assertEquals("Never gonna give", cleanLyrics(">> Never gonna give"))
    }

    @Test
    fun `a single leading angle is removed too`() {
        // The gap this test exists for. YouTube uses ">>" for a change of speaker and a lone ">"
        // where the same one carries on, and the old rule needed two - so every continuation line
        // kept its bracket.
        assertEquals("Never gonna give", cleanLyrics("> Never gonna give"))
        assertEquals("Never gonna give", cleanLyrics(">Never gonna give"))
        assertEquals("Never gonna give", cleanLyrics(">>> Never gonna give"))
    }

    @Test
    fun `an angle bracket mid-line is left alone`() {
        // Out of the position the convention puts it in, it is far likelier to be something the
        // lyricist wrote - and there is no way to tell the two apart once it is.
        assertEquals("you > me", cleanLyrics("you > me"))
    }

    @Test
    fun `markers are removed from inside a timestamped line`() {
        assertEquals("[00:12.00]Never gonna give", cleanLyrics("[00:12.00]>> Never gonna give"))
        assertEquals("[00:12.00]Never gonna give", cleanLyrics("[00:12.00]> Never gonna give"))
    }

    // ---- sound annotations -------------------------------------------------------------------------

    @Test
    fun `a music annotation inside a line is removed`() {
        assertEquals("Never gonna give", cleanLyrics("Never [Music] gonna give"))
        assertEquals("Never gonna give", cleanLyrics("[MUSIC PLAYING] Never gonna give"))
    }

    @Test
    fun `a singing annotation is removed`() {
        assertEquals("Never gonna give", cleanLyrics("[Singing] Never gonna give"))
        assertEquals("Never gonna give", cleanLyrics("[Music and singing] Never gonna give"))
    }

    @Test
    fun `a line that is only a music annotation becomes a note`() {
        assertEquals(note, cleanLyrics("[Music]"))
        assertEquals(note, cleanLyrics("[MUSIC PLAYING]"))
        assertEquals(note, cleanLyrics("[Instrumental]"))
        assertEquals("[00:30.00]$note", cleanLyrics("[00:30.00][Music]"))
    }

    @Test
    fun `a line that is only a singing annotation becomes nothing`() {
        // It was labelling the obvious. A note in its place would claim there was an instrumental
        // break where there was in fact a vocal.
        assertEquals("", cleanLyrics("[Singing]"))
        assertEquals("[00:30.00]", cleanLyrics("[00:30.00][Singing]"))
    }

    @Test
    fun `annotations about the audience are kept`() {
        // These are part of a live recording rather than an artefact of transcribing one.
        assertEquals("[Applause]", cleanLyrics("[Applause]"))
        assertEquals("[Laughter]", cleanLyrics("[Laughter]"))
    }

    @Test
    fun `the recogniser's own failures are removed without a placeholder`() {
        // Unlike a music annotation these describe a failure of the transcript, not an event in
        // the recording, so there is nothing for a note to stand in for.
        assertEquals("", cleanLyrics("[inaudible]"))
        assertEquals("Never gonna give", cleanLyrics("Never [unintelligible] gonna give"))
    }

    // ---- notes wrapping a line ----------------------------------------------------------------------

    @Test
    fun `notes wrapped around words are decoration and go`() {
        // YouTube brackets sung lines this way. Across a whole song it reads as decoration applied
        // to every line rather than as a mark meaning anything.
        assertEquals("Never gonna give", cleanLyrics("♪ Never gonna give ♪"))
        assertEquals("Never gonna give", cleanLyrics("♪Never gonna give♪"))
        assertEquals("[00:12.00]Never gonna give", cleanLyrics("[00:12.00]♪ Never gonna give ♪"))
    }

    @Test
    fun `a line of nothing but notes is the mark itself and stays`() {
        assertEquals(note, cleanLyrics(note))
        assertTrue(cleanLyrics("♪♪").isNotEmpty())
    }

    // ---- what must not be damaged --------------------------------------------------------------------

    @Test
    fun `an ordinary lyric sheet passes through untouched`() {
        val lyrics = """
            [00:12.00]Never gonna give you up
            [00:15.00]Never gonna let you down
            [00:18.00]Never gonna run around and desert you
        """.trimIndent()
        assertEquals(lyrics, cleanLyrics(lyrics))
    }

    @Test
    fun `cleaning twice changes nothing the second time`() {
        // It runs on what gets stored and again on what gets displayed, so this is load-bearing
        // rather than a nicety.
        val messy = ">> ♪ Never [Music] gonna give ♪\n[00:30.00][Music]\n> And again"
        val once = cleanLyrics(messy)
        assertEquals(once, cleanLyrics(once))
    }

    @Test
    fun `word timings survive the cleaner`() {
        // The regression this change could plausibly have caused: the cleanup now runs on every
        // lyric on its way to the screen, enhanced LRC included, and that format is full of angle
        // brackets. `<00:01.00>` is not a speaker marker and must not be treated as one - losing
        // those silently downgrades a word-timed sheet to a line-timed one, which looks like the
        // provider having sent less rather than like anything here.
        val enhanced = "[00:01.00]<00:01.00>Never <00:01.50>gonna <00:02.00>give"
        assertEquals(enhanced, cleanLyrics(enhanced))
    }

    @Test
    fun `a speaker marker is still removed from a word-timed line`() {
        assertEquals(
            "[00:01.00]<00:01.00>Never <00:01.50>gonna",
            cleanLyrics("[00:01.00]>> <00:01.00>Never <00:01.50>gonna"),
        )
    }

    @Test
    fun `blank and empty input survive`() {
        assertEquals("", cleanLyrics(""))
        assertEquals("", cleanLyrics("   "))
    }

    @Test
    fun `timestamps are never altered`() {
        val stamped = "[00:12.34]>> [Music] Never gonna give"
        assertTrue(cleanLyrics(stamped).startsWith("[00:12.34]"))
    }
}
