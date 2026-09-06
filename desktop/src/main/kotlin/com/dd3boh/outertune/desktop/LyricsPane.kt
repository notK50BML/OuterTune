/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A song's words, following along where the file has timings.
 *
 * The current line is bright and the rest are dimmed rather than the current line being tinted some
 * accent colour. Lyrics are read as a block with one's place kept in it, so what matters is which
 * line the eye lands on, and brightness carries that far better than hue - particularly over a
 * background taken from album art, which is already some arbitrary colour.
 *
 * Any line can be clicked to seek to it. That is the one thing lyrics can do that a seek bar cannot:
 * jumping to a remembered line is how people navigate a song they know, and it is far more precise
 * than dragging to a position.
 *
 * Unsynced lyrics are shown as plain scrollable text with nothing highlighted. Inventing a highlight
 * for a file with no timings would be making up the one piece of information it does not have.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onColour: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = onColour.copy(alpha = 0.6f))

            lyrics == null || lyrics.isEmpty -> Text(
                text = "No lyrics found",
                style = MaterialTheme.typography.bodyLarge,
                color = onColour.copy(alpha = 0.5f),
            )

            !lyrics.synced -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                lyrics.lines.forEach { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize),
                        color = onColour.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            else -> SyncedLyrics(
                lyrics = lyrics,
                positionMs = positionMs,
                onSeek = onSeek,
                onColour = onColour,
                fontSize = fontSize,
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onColour: Color,
    fontSize: TextUnit,
) {
    val listState = rememberLazyListState()
    val current = remember(lyrics, positionMs) { LrcParser.lineAt(lyrics.lines, positionMs) }

    // Scrolling stops following while the pointer is driving the list, and starts again a few
    // seconds after it stops. Without that, looking ahead at the next verse is impossible: the list
    // yanks itself back on every line change, which arrives every couple of seconds.
    var browsingUntil by remember { mutableStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) browsingUntil = System.currentTimeMillis() + BROWSE_GRACE_MS
    }

    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        if (System.currentTimeMillis() < browsingUntil) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        // Centred rather than scrolled to the top, so there is as much of what is coming as of what
        // has just been sung. The offset is half the viewport, which is what "centre" means when
        // every line is a different height.
        val centreOffset = -(listState.layoutInfo.viewportSize.height / 2) +
            (visible.firstOrNull { it.index == current }?.size ?: 0) / 2
        listState.animateScrollToItem(current.coerceAtLeast(0), centreOffset)
    }

    LazyColumn(
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // Half a screen of padding at each end so the first and last lines can still reach the
        // middle. Without it the song opens with its first line pinned to the top and ends with the
        // last one pinned to the bottom, and the highlight stops tracking at both ends.
        contentPadding = PaddingValues(vertical = 180.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == current
            val alpha by animateFloatAsState(if (active) 1f else 0.42f)
            if (line.text.isBlank()) {
                // An empty timed line is an instrumental break. Shown as space rather than as
                // nothing, so a long gap reads as part of the song instead of as lyrics stopping.
                Spacer(modifier = Modifier.height(fontSize.value.dp))
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = onColour.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { line.timeMs?.let(onSeek) }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** How long after a manual scroll before the lyrics start following the song again. */
private const val BROWSE_GRACE_MS = 4_000L
