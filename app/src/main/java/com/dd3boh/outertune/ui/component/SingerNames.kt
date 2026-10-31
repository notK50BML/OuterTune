/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.betterlyrics.Singer
import com.dd3boh.betterlyrics.SingerColours
import com.dd3boh.betterlyrics.SingerNames
import com.dd3boh.betterlyrics.Singers
import com.dd3boh.betterlyrics.Voice
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpeakerEntity

/*
 * Settings > Lyrics > Singer names in lyrics, on the phone - the same three choices, worked out by
 * the same shared code in `:betterlyrics`, as on the desktop.
 */

/**
 * The voice a line's speaker is coloured as. The phone's TTML reader gives a duet's lines Voice1,
 * Voice2 or Group (and their background forms), and its LRC reader does the same for `v1:`, `v2:`
 * and `v3:` - which is how a document only the lenient reader could read still carries its singers
 * (see TTMLParser.toLrc). Walaoke's gender marks are left one colour, as the desktop leaves them.
 */
val SpeakerEntity?.voice: Voice?
    get() = when (this) {
        SpeakerEntity.Voice1, SpeakerEntity.Background -> Voice.ONE
        SpeakerEntity.Voice2, SpeakerEntity.Voice2Background -> Voice.TWO
        SpeakerEntity.Group, SpeakerEntity.GroupBackground -> Voice.GROUP
        else -> null
    }

/** Whether more than one voice sings these lines - the only time colouring them by singer says anything. */
fun List<SemanticLyrics.LyricLine>.isMultiVoice(): Boolean = Singers.isMultiVoice(map { it.speaker.voice })

/**
 * These lyrics without the singers' names written into their lines ("Latto: Uh, yeah"), unless
 * [mode] is [SingerNames.TEXT], which shows them exactly as they came. Colouring is not done here -
 * the lines keep their speakers either way, and the view decides whether to colour by them.
 *
 * A name written into the text is a short leading name and its colon on lines of a song that is
 * labelled that way throughout - see Singers.namePrefixes for how cautious that is. A line that was
 * nothing but a name ("Latto:", heading her verse) goes with it. Word timings are moved along with
 * the text they index into, and the words that spelt the name go.
 *
 * Applied on the way to the screen, so the stored lyrics keep what the provider sent and changing
 * the setting needs no fetch.
 */
fun SemanticLyrics?.withSingerNames(mode: SingerNames): SemanticLyrics? {
    if (this == null || this == uninitializedLyric || mode == SingerNames.TEXT) return this
    return when (this) {
        is SemanticLyrics.SyncedLyrics -> {
            // Translations are a line's meaning in another language, not a line of their own.
            val names = Singers.namePrefixes(text.filterNot { it.isTranslated }.map { it.text })
            if (names.isEmpty()) return this
            SemanticLyrics.SyncedLyrics(
                text.mapNotNull { line ->
                    val cut = if (line.isTranslated) 0 else Singers.namePrefixLength(line.text, names)
                    if (cut == 0) return@mapNotNull line
                    val rest = line.text.substring(cut)
                    if (rest.isBlank()) return@mapNotNull null
                    line.copy(
                        text = rest,
                        // Fresh words rather than the old ones moved: the parsed lyrics are shared,
                        // and their words are mutable.
                        words = line.words
                            ?.mapNotNull { word ->
                                if (word.charRange.last < cut) return@mapNotNull null
                                word.copy(charRange = (word.charRange.first - cut).coerceAtLeast(0)..(word.charRange.last - cut))
                            }
                            ?.takeIf { it.isNotEmpty() }
                            ?.toMutableList(),
                    )
                }
            )
        }

        is SemanticLyrics.UnsyncedLyrics -> {
            val names = Singers.namePrefixes(unsyncedText.map { it.first })
            if (names.isEmpty()) return this
            SemanticLyrics.UnsyncedLyrics(
                unsyncedText.mapNotNull { (line, speaker) ->
                    val cut = Singers.namePrefixLength(line, names)
                    val rest = line.substring(cut)
                    if (cut > 0 && rest.isBlank()) null else rest to speaker
                }
            )
        }
    }
}

/**
 * The colour each voice's lines are drawn in over [onColour], the colour ordinary lyrics use there -
 * worked out by `:betterlyrics`' [SingerColours] from the theme's primary and tertiary, the same way
 * the desktop does. Lines with no voice stay [onColour].
 */
@Composable
fun rememberVoiceColours(onColour: Color): (Voice?) -> Color {
    val scheme = MaterialTheme.colorScheme
    return remember(onColour, scheme.primary, scheme.tertiary) {
        val colours = Voice.entries.associateWith { voice ->
            Color(SingerColours.forVoice(voice, scheme.primary.toArgb(), scheme.tertiary.toArgb(), onColour.toArgb()))
        }
        val colourOf: (Voice?) -> Color = { voice -> voice?.let(colours::getValue) ?: onColour }
        colourOf
    }
}

/**
 * Who is who in a coloured duet: a dot of each voice's colour and the name of whoever sings in it,
 * "Jung Kook · Latto". Nothing at all when the lyrics name nobody.
 */
@Composable
fun SingerLegend(singers: List<Singer>, colourOf: (Voice?) -> Color, modifier: Modifier = Modifier) {
    val entries = remember(singers) { Singers.legend(singers) }
    if (entries.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, (voice, name) ->
            if (index > 0) {
                Text(
                    text = "·",
                    fontSize = 12.sp,
                    color = colourOf(null).copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Box(Modifier.size(8.dp).background(colourOf(voice), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text = name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colourOf(voice), maxLines = 1)
        }
    }
}
