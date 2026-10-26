/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A lyrics provider the desktop can ask, as its id in [Settings.lyricsProviderOrder] and in the cache. */
enum class LyricsSource(val id: String, val label: String, val blurb: String) {
    BetterLyrics(LyricsRepository.BETTERLYRICS, "BetterLyrics", "Times each word; often refuses songs it has not cached"),
    LrcLib(LyricsRepository.LRCLIB, "LRCLIB", "Line-timed lyrics, from a large open library"),
    KuGou(LyricsRepository.KUGOU, "KuGou", "Strongest for Chinese-language songs"),
    ;

    companion object {
        /** BetterLyrics first - word timings are strictly more than line timings - then LRCLIB, then KuGou. */
        val DEFAULT = listOf(BetterLyrics, LrcLib, KuGou)

        /** The stored order, with any provider it does not mention appended in the default order. */
        fun ordered(stored: String): List<LyricsSource> {
            val named = stored.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }.distinct()
            return named + DEFAULT.filterNot { it in named }
        }
    }
}

/** How the providers are asked - the phone's "Provider selection". */
enum class LyricsFetchMode(val label: String, val description: String) {
    Automatic("Automatic (fastest)", "Asks every provider at once and uses the first synced lyrics back."),
    InMyOrder("In my order", "Asks one at a time from the top of your list; slower, but you decide who answers."),
}

/** Whether [source] is switched on, from the settings the three switches always used. */
fun Settings.isEnabled(source: LyricsSource): Boolean = when (source) {
    LyricsSource.BetterLyrics -> wordByWordLyrics
    LyricsSource.LrcLib -> lyricsLrclib
    LyricsSource.KuGou -> lyricsKugou
}

fun Settings.setEnabled(source: LyricsSource, on: Boolean) = when (source) {
    LyricsSource.BetterLyrics -> wordByWordLyrics = on
    LyricsSource.LrcLib -> lyricsLrclib = on
    LyricsSource.KuGou -> lyricsKugou = on
}

/**
 * Which lyrics providers are asked, and in what order - ticked and dragged, as the tabs are. Top of
 * the list is asked first. One list in place of a switch per provider and a fixed order.
 */
@Composable
fun LyricsProvidersDialog(settings: Settings, onDismiss: () -> Unit) {
    val items = remember {
        mutableStateListOf<Pair<LyricsSource, Boolean>>().apply {
            addAll(LyricsSource.ordered(settings.lyricsProviderOrder).map { it to settings.isEnabled(it) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics providers") },
        confirmButton = {
            TextButton(onClick = {
                settings.lyricsProviderOrder = items.joinToString(",") { it.first.id }
                items.forEach { (source, on) -> settings.setEnabled(source, on) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    items.clear()
                    items.addAll(LyricsSource.DEFAULT.map { it to true })
                }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(modifier = Modifier.width(420.dp)) {
                Text(
                    "Asked from the top until one has lyrics for the song. Untick one to never ask it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ReorderableColumn(
                    items = items,
                    key = { it.first },
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                ) { index, (source, on), handle ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { items[index] = source to !on }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.label)
                            Text(
                                source.blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DragHandle(handle)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
        },
    )
}
