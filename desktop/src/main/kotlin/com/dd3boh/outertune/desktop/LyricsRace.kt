/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * How much timing one provider's lyrics carry, best first.
 *
 * The phone's FoundKind (app/.../lyrics/LyricsHelper.kt), with one rung it does not have.
 * [Transcript] is a caption track: timed, but a transcript - often the speech recogniser's - rather
 * than a lyric sheet. It sits under a real line-timed file and over plain words, which is where the
 * desktop has always put captions; the phone counts them as line-timed and leaves the order to
 * keep them under a real file, which a reordered list does not.
 */
enum class LyricsQuality { WordSynced, LineSynced, Transcript, Plain }

/** What [lyrics] from [source] count as - see [LyricsQuality]. */
fun qualityOf(source: LyricsSource, lyrics: Lyrics): LyricsQuality = when {
    lyrics.wordSynced -> LyricsQuality.WordSynced
    !lyrics.synced -> LyricsQuality.Plain
    source.transcript -> LyricsQuality.Transcript
    else -> LyricsQuality.LineSynced
}

/**
 * Folds the providers' answers for one song into the one that is kept - the phone's
 * RemoteLyricsAggregator, the same rules in the same order.
 *
 * Two things decide which answer wins: **how much timing it carries**, and then **where its provider
 * sits in [order]**. The second is only ever a tie-break. Answers arrive in network order, and
 * keeping the first of each kind to arrive meant the quickest provider won every tie - the order
 * decided who was asked rather than who was believed.
 *
 * The lookup can stop early once a word-timed answer is in hand *and* every provider ranked above
 * it has answered, since nothing still out could beat it then. A line-timed answer never stops it:
 * a slower provider could still come back with word timings, and that wait is the one worth
 * paying, since word timings are what the sweep is drawn from. The deadline the caller sets bounds
 * the wait either way.
 *
 * The desktop used to take the first synced answer back outright, and in its in-order mode the
 * first answer of any kind. Both meant a song with word-timed lyrics a little further down the
 * list, or a little slower to answer, got line timings - or none - instead.
 */
class LyricsRace(private val order: List<LyricsSource>) {

    /** One provider's kept answer: the text to store and the lyrics it parses to. */
    data class Found(val source: LyricsSource, val text: String, val lyrics: Lyrics, val quality: LyricsQuality)

    /** The answer that wins so far, or null when none has had any lyrics. */
    var best: Found? = null
        private set

    private val answered = HashSet<LyricsSource>()

    private fun rankOf(source: LyricsSource): Int = order.indexOf(source).takeIf { it >= 0 } ?: Int.MAX_VALUE

    /**
     * Folds in [source]'s answer - [found] as the text and its lyrics, or null for none or a failure.
     *
     * @return true once nothing still to answer could improve on what is held.
     */
    fun offer(source: LyricsSource, found: Pair<String, Lyrics>?): Boolean {
        answered += source
        if (found != null) {
            val (text, lyrics) = found
            val quality = qualityOf(source, lyrics)
            val held = best
            // A better kind wins outright; within a kind the order breaks the tie. Lower ordinal is
            // better, as the enum is written best first.
            val better = held == null ||
                quality.ordinal < held.quality.ordinal ||
                (quality == held.quality && rankOf(source) < rankOf(held.source))
            if (better) best = Found(source, text, lyrics, quality)
        }
        return canStop()
    }

    private fun canStop(): Boolean {
        val held = best ?: return false
        if (held.quality != LyricsQuality.WordSynced) return false
        return order.take(rankOf(held.source).coerceAtMost(order.size)).all { it in answered }
    }
}
