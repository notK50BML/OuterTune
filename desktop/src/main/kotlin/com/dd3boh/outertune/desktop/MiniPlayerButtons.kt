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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What can sit on the mini player between the song and its playback controls.
 *
 * Its own list rather than [PlayerButtonKind]'s: the full player's buttons act on the song (like,
 * download, add to a playlist), where most of these act on playback (shuffle, repeat, volume) - and
 * the mini player has a fraction of the room, so the two are chosen separately. Stored as one letter
 * each, as the player buttons are; the letters they share mean the same thing in both.
 */
enum class MiniPlayerButtonKind(val code: Char, val label: String) {
    CLOCK('C', "Clock"),
    LIKE('L', "Like"),
    QUEUE('Q', "Queue"),
    SHUFFLE('H', "Shuffle"),
    REPEAT('R', "Repeat"),
    VOLUME('V', "Volume"),
    LYRICS('Y', "Lyrics"),
    SLEEP('S', "Sleep timer"),
    ;

    /** Its glyph, for the editor. The clock has none - it is drawn as the time it shows. */
    val icon: ImageVector?
        get() = when (this) {
            CLOCK -> null
            LIKE -> OuterTuneIcons.favoriteBorder
            QUEUE -> OuterTuneIcons.queueMusicRounded
            SHUFFLE -> OuterTuneIcons.shuffle
            REPEAT -> OuterTuneIcons.repeat
            VOLUME -> OuterTuneIcons.volumeUp
            LYRICS -> OuterTuneIcons.lyrics
            SLEEP -> OuterTuneIcons.bedtime
        }

    companion object {
        /**
         * The queue button alone. The phone's mini player has no extras at all, but the phone's queue
         * is a swipe up from the player; here the panel beside the list has no other button, and a
         * default that hid it would leave it reachable only by a shortcut.
         */
        const val DEFAULT = "Q"

        /** Stored for "none at all", for the same reason as PlayerButtonKind.NONE. */
        const val NONE = "-"

        fun decode(stored: String): List<MiniPlayerButtonKind> =
            stored.ifEmpty { DEFAULT }.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct()

        fun encode(buttons: List<MiniPlayerButtonKind>): String =
            buttons.distinct().joinToString("") { it.code.toString() }.ifEmpty { NONE }
    }
}

/**
 * Choosing and ordering the mini player's extras - laid out as PlayerButtonsDialog is, so the two
 * editors read as one design.
 *
 * The preview is the bar itself in miniature, the chosen items in their places beside previous,
 * play and next, because where they land relative to those is the whole question being answered.
 */
@Composable
fun MiniPlayerButtonsDialog(
    current: List<MiniPlayerButtonKind>,
    onSave: (List<MiniPlayerButtonKind>) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember {
        mutableStateListOf<Pair<MiniPlayerButtonKind, Boolean>>().apply {
            addAll(current.map { it to true })
            addAll(MiniPlayerButtonKind.entries.filterNot { it in current }.map { it to false })
        }
    }
    val chosen = items.filter { it.second }.map { it.first }

    fun reset() {
        val defaults = MiniPlayerButtonKind.decode(MiniPlayerButtonKind.DEFAULT)
        items.clear()
        items.addAll(defaults.map { it to true })
        items.addAll(MiniPlayerButtonKind.entries.filterNot { it in defaults }.map { it to false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mini player buttons") },
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
                    Text(
                        "♪  Song title",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    chosen.forEach { MiniPlayerButtonGlyph(it) }
                    // The playback controls, which are always there and not part of the choice -
                    // drawn fainter so the eye goes to what is being arranged.
                    listOf(OuterTuneIcons.skipPrevious, OuterTuneIcons.play, OuterTuneIcons.skipNext).forEach {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp).alpha(0.45f),
                        )
                    }
                }
                Hint("Top of the list is the leftmost. Click to turn on or off, drag the handle to reorder.")
                if (MiniPlayerButtonKind.QUEUE !in chosen) {
                    Hint("Without Queue, the queue beside the list opens only from the full player or its keyboard shortcut.", error = true)
                }
                Spacer(modifier = Modifier.height(8.dp))
                ReorderableColumn(
                    items = items,
                    key = { it.first },
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState()),
                ) { index, (button, on), handle ->
                    val position = chosen.indexOf(button)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { items[index] = button to !on }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                        // Plain glyphs, not the player dialog's discs: on the bar these are plain
                        // icon buttons, and the preview should look like what it previews.
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp).alpha(if (on) 1f else 0.45f)) {
                            MiniPlayerButtonGlyph(button)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            button.label,
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

/** One item as the editor draws it: its icon, or for the clock a sample time. */
@Composable
private fun MiniPlayerButtonGlyph(button: MiniPlayerButtonKind) {
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    val icon = button.icon
    if (icon == null) {
        Text("1:50", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
