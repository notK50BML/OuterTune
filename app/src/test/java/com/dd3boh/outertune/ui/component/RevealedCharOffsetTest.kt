package com.dd3boh.outertune.ui.component

import org.akanework.gramophone.logic.utils.SemanticLyrics.Word
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [revealedCharOffset] is the whole of the karaoke sweep's timing model: everything the renderer
 * draws follows from the character offset this returns for a playback position.
 *
 * The text these ranges describe is "Never gonna give", laid out the way the lyric parser produces
 * it — a word's char range covers the word only, never the space after it.
 */
class RevealedCharOffsetTest {

    private val text = "Never gonna give"
    private val words = listOf(
        Word(timeRange = 1000uL..1499uL, charRange = 0..4, isRtl = false),   // Never
        Word(timeRange = 2000uL..2499uL, charRange = 6..10, isRtl = false),  // gonna
        Word(timeRange = 2500uL..2999uL, charRange = 12..15, isRtl = false), // give
    )

    private fun at(positionMs: Long) = revealedCharOffset(words, text.length, positionMs)

    @Test
    fun beforeTheFirstWord_nothingIsRevealed() {
        assertEquals(0f, at(0), 0.001f)
        assertEquals(0f, at(999), 0.001f)
    }

    @Test
    fun negativePosition_isTreatedAsTheStart() {
        assertEquals(0f, at(-5000), 0.001f)
    }

    @Test
    fun withinAWord_theOffsetAdvancesEvenlyAcrossItsCharacters() {
        // "Never" spans chars 0..4, so the whole word is 5 characters wide.
        assertEquals(0f, at(1000), 0.001f)
        assertEquals(2.5f, at(1250), 0.02f)
        assertEquals(5f, at(1499), 0.02f)
    }

    /**
     * Between words the offset holds at the end of the word that just finished. The gap is the
     * space between them, which has no ink, so holding still there is invisible - whereas creeping
     * through it would put the boundary inside the next word before it has been sung.
     */
    @Test
    fun betweenWords_theOffsetHoldsAtTheEndOfTheFinishedWord() {
        assertEquals(5f, at(1500), 0.001f)
        assertEquals(5f, at(1750), 0.001f)
        assertEquals(5f, at(1999), 0.001f)
    }

    @Test
    fun theOffsetJumpsToTheNextWordsFirstCharacterWhenItStarts() {
        assertEquals(6f, at(2000), 0.001f)
    }

    @Test
    fun afterTheLastWord_theWholeLineIsRevealed() {
        assertEquals(16f, at(3000), 0.001f)
        assertEquals(16f, at(600_000), 0.001f)
    }

    @Test
    fun theOffsetIsClampedToTheTextLength() {
        // A provider whose last char range runs past the text must not push the boundary out of the
        // layout, which would make the renderer ask for a bounding box that does not exist.
        val overrunning = listOf(Word(timeRange = 1000uL..1499uL, charRange = 0..99, isRtl = false))
        assertEquals(text.length.toFloat(), revealedCharOffset(overrunning, text.length, 5000), 0.001f)
    }

    /**
     * Some providers emit words whose ranges overlap by a few milliseconds. Taking the maximum
     * keeps the sweep monotonic instead of letting it flick backwards on those frames.
     */
    @Test
    fun overlappingWords_neverMoveTheOffsetBackwards() {
        val overlapping = listOf(
            Word(timeRange = 1000uL..2000uL, charRange = 0..4, isRtl = false),
            Word(timeRange = 1900uL..2400uL, charRange = 6..10, isRtl = false),
        )
        val early = revealedCharOffset(overlapping, text.length, 1950)
        val late = revealedCharOffset(overlapping, text.length, 1990)
        assertEquals(true, late >= early)
        assertEquals(true, early >= 5f)
    }

    @Test
    fun noWords_revealsNothing() {
        assertEquals(0f, revealedCharOffset(emptyList(), text.length, 5000), 0.001f)
    }

    /**
     * A zero-length word would otherwise divide by zero. It is treated as instantaneous.
     */
    @Test
    fun zeroLengthWord_doesNotProduceANonFiniteOffset() {
        val instant = listOf(Word(timeRange = 1000uL..1000uL, charRange = 0..4, isRtl = false))
        assertEquals(5f, revealedCharOffset(instant, text.length, 1000), 0.001f)
    }
}
