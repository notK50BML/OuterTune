/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.SongItem

/**
 * What can show under a song's title on its row: "Daft Punk • Random Access Memories • 5:37".
 *
 * Stored as one letter each, in order, as the player buttons are - and the same letters as the
 * phone's Song details, so the two read each other's choice.
 */
enum class SongDetail(val code: Char, val label: String) {
    ARTISTS('A', "Artists"),
    ALBUM('L', "Album"),
    LENGTH('D', "Length"),
    ;

    companion object {
        /**
         * All three. The phone keeps to the artists and the length it always showed; here a row has
         * the width for the album too, and it is what tells two songs of one name apart.
         */
        const val DEFAULT = "ALD"

        /** Stored for "none at all", for the same reason as MiniPlayerButtonKind.NONE. */
        const val NONE = "-"

        fun decode(stored: String): List<SongDetail> =
            stored.ifEmpty { DEFAULT }.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct()

        fun encode(details: List<SongDetail>): String =
            details.distinct().joinToString("") { it.code.toString() }.ifEmpty { NONE }

        /**
         * The line itself: each chosen detail the song has, in the chosen order, joined by " • ".
         * A song with no album, or no known length, simply leaves that part out.
         */
        fun line(details: List<SongDetail>, artists: String?, album: String?, length: String?): String =
            details.mapNotNull {
                when (it) {
                    ARTISTS -> artists
                    ALBUM -> album
                    LENGTH -> length
                }?.takeIf { part -> part.isNotBlank() }
            }.joinToString(" • ")
    }
}

/** The chosen details, in order - provided once at the top of the window, from the setting. */
val LocalSongDetails = compositionLocalOf { SongDetail.decode("") }

/** What the library knows of a song beyond its title and credits - see Database.songFacts. */
data class SongFacts(val album: String, val durationMs: Long)

/** Every song's [SongFacts] by id, for rows whose own data has no album or length. */
val LocalSongFacts = compositionLocalOf { emptyMap<String, SongFacts>() }

/**
 * The line under a song's title, as Settings > Library and content > Song details has it. Every song
 * row, card and menu builds its subtitle here, so the setting reaches all of them alike.
 *
 * [artists] is already the shown credit (SongItem.shown, StoredSong.shownArtists). The album and
 * length are the song's own where it carries them - a search result, an album's tracks - and the
 * library's otherwise: a library song is stored without either. [showAlbum] is false on an album's
 * own page, where the album under every title would only repeat the page's.
 */
@Composable
internal fun songDetailsLine(
    id: String,
    artists: String,
    album: String?,
    durationMs: Long?,
    showAlbum: Boolean = true,
): String {
    val facts = LocalSongFacts.current[id]
    return SongDetail.line(
        details = LocalSongDetails.current.filter { showAlbum || it != SongDetail.ALBUM },
        artists = artists.ifBlank { "Unknown artist" },
        album = album?.takeIf { it.isNotBlank() } ?: facts?.album,
        length = (durationMs?.takeIf { it > 0 } ?: facts?.durationMs?.takeIf { it > 0 })?.let(::formatClock),
    )
}

/** A song from YouTube's data. Pass it through [shown] first, as the rows do, for repaired credits. */
@Composable
internal fun SongItem.detailsLine(showAlbum: Boolean = true): String =
    songDetailsLine(id, artists.joinToString { it.name }, album?.name, duration?.let { it * 1000L }, showAlbum)

/** A library song, its artists as shown - its credits under their current names. */
@Composable
internal fun StoredSong.detailsLine(): String =
    songDetailsLine(id, shownArtists(LocalCredits.current), album = null, durationMs = null)

/**
 * Choosing and ordering what shows under a song's title - laid out as PlayerButtonsDialog and
 * MiniPlayerButtonsDialog are, so the editors read as one design. The preview is a song row with a
 * sample song that has all three to show.
 */
@Composable
fun SongDetailsDialog(
    current: List<SongDetail>,
    onSave: (List<SongDetail>) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember {
        mutableStateListOf<Pair<SongDetail, Boolean>>().apply {
            addAll(current.map { it to true })
            addAll(SongDetail.entries.filterNot { it in current }.map { it to false })
        }
    }
    val chosen = items.filter { it.second }.map { it.first }

    fun reset() {
        val defaults = SongDetail.decode(SongDetail.DEFAULT)
        items.clear()
        items.addAll(defaults.map { it to true })
        items.addAll(SongDetail.entries.filterNot { it in defaults }.map { it to false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song details") },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = ::reset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(modifier = Modifier.width(460.dp)) {
                Text(
                    "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A song row as SongRow draws one, with a plain square for the cover.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            OuterTuneIcons.musicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(SAMPLE_TITLE, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = SongDetail.line(chosen, SAMPLE_ARTISTS, SAMPLE_ALBUM, SAMPLE_LENGTH)
                        if (line.isNotEmpty()) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Hint("Top of the list comes first. Click to turn on or off, drag the handle to reorder.")
                Hint("Album shows only for songs that have one, and never on an album's own page.")
                Spacer(modifier = Modifier.height(8.dp))
                ReorderableColumn(
                    items = items,
                    key = { it.first },
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                ) { index, (detail, on), handle ->
                    val position = chosen.indexOf(detail)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { items[index] = detail to !on }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = if (on) 0f else 0.6f),
                                    CircleShape,
                                ),
                        ) {
                            if (on) {
                                Text(
                                    "${position + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            detail.label,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        DragHandle(handle)
                    }
                }
            }
        },
    )
}

// The preview's song. A real one, so the line reads as a real row would.
private const val SAMPLE_TITLE = "Song title"
private const val SAMPLE_ARTISTS = "Artist"
private const val SAMPLE_ALBUM = "Album"
private const val SAMPLE_LENGTH = "5:37"
