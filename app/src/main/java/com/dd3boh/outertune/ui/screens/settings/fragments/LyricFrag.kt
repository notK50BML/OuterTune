/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextRotationAngledown
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.constants.EnableLyricsPrefetchKey
import com.dd3boh.outertune.constants.EnableSimpMusicKey
import com.dd3boh.outertune.constants.LyricClickable
import com.dd3boh.outertune.constants.LyricFontSizeKey
import com.dd3boh.outertune.constants.LyricEstimatedWordSync
import com.dd3boh.outertune.constants.LyricKaraokeEnable
import com.dd3boh.outertune.constants.LyricOffsetKey
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.LyricUpdateSpeed
import com.dd3boh.outertune.constants.LyricsFetchMode
import com.dd3boh.outertune.constants.LyricsFetchModeKey
import com.dd3boh.outertune.constants.LyricsPosition
import com.dd3boh.outertune.constants.LyricsPrefetchCountKey
import com.dd3boh.outertune.constants.LyricsProviderOrderKey
import com.dd3boh.outertune.constants.LyricsTextPositionKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.constants.Speed
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.move
import com.dd3boh.outertune.lyrics.LyricsProvider
import com.dd3boh.outertune.lyrics.REMOTE_LYRICS_PROVIDERS
import com.dd3boh.outertune.lyrics.orderProviders
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.CounterDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun ColumnScope.LyricFormatFrag() {
    val (lyricsPosition, onLyricsPositionChange) = rememberEnumPreference(
        LyricsTextPositionKey,
        defaultValue = LyricsPosition.CENTER
    )
    val (lyricFontSize, onLyricFontSizeChange) = rememberPreference(LyricFontSizeKey, defaultValue = 20)

    var showFontSizeDialog by remember {
        mutableStateOf(false)
    }

    EnumListPreference(
        title = { Text(stringResource(R.string.lyrics_text_position)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        selectedValue = lyricsPosition,
        onValueSelected = onLyricsPositionChange,
        valueText = {
            when (it) {
                LyricsPosition.LEFT -> stringResource(R.string.left)
                LyricsPosition.CENTER -> stringResource(R.string.center)
                LyricsPosition.RIGHT -> stringResource(R.string.right)
            }
        }
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.lyrics_font_Size)) },
        description = "$lyricFontSize sp",
        icon = { Icon(Icons.Rounded.TextFields, null) },
        onClick = { showFontSizeDialog = true }
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showFontSizeDialog) {
        CounterDialog(
            title = stringResource(R.string.lyrics_font_Size),
            initialValue = lyricFontSize,
            upperBound = 32,
            lowerBound = 8,
            unitDisplay = " pt",
            onDismiss = { showFontSizeDialog = false },
            onConfirm = {
                onLyricFontSizeChange(it)
                showFontSizeDialog = false
            },
            onReset = { onLyricFontSizeChange(20) },
            onCancel = { showFontSizeDialog = false }
        )
    }
}


@Composable
fun ColumnScope.LyricParserFrag() {
    val (multilineLrc, onMultilineLrcChange) = rememberPreference(MultilineLrcKey, defaultValue = true)
    val (lyricTrim, onLyricTrimChange) = rememberPreference(LyricTrimKey, defaultValue = false)

    // multiline lyrics
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_multiline_title)) },
        description = stringResource(R.string.lyrics_multiline_description),
        icon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
        checked = multilineLrc,
        onCheckedChange = onMultilineLrcChange
    )

    // trim (remove spaces around) lyrics
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_trim_title)) },
        icon = { Icon(Icons.Rounded.ContentCut, null) },
        checked = lyricTrim,
        onCheckedChange = onLyricTrimChange
    )
}

@Composable
fun ColumnScope.LyricSourceFrag() {
    val (enableKugou, onEnableKugouChange) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableLrcLib, onEnableLrcLibChange) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (enableBetterLyrics, onEnableBetterLyricsChange) = rememberPreference(key = EnableBetterLyricsKey, defaultValue = true)
    val (enableSimpMusic, onEnableSimpMusicChange) = rememberPreference(key = EnableSimpMusicKey, defaultValue = true)
    val (preferLocalLyric, onPreferLocalLyric) = rememberPreference(LyricSourcePrefKey, defaultValue = true)
    val (enablePrefetch, onEnablePrefetchChange) = rememberPreference(EnableLyricsPrefetchKey, defaultValue = true)
    val (prefetchCount, onPrefetchCountChange) = rememberPreference(LyricsPrefetchCountKey, defaultValue = 3)
    val (fetchMode, onFetchModeChange) = rememberEnumPreference(LyricsFetchModeKey, LyricsFetchMode.AUTO)
    val (providerOrder, onProviderOrderChange) = rememberPreference(LyricsProviderOrderKey, defaultValue = "")

    var showPrefetchCountDialog by remember {
        mutableStateOf(false)
    }
    var showProviderOrder by remember {
        mutableStateOf(false)
    }

    /**
     * Working copy of the order, edited by dragging and only written back when the dialog is
     * confirmed, so an abandoned drag does not silently change which provider answers first.
     */
    val mutableProviders = remember { mutableStateListOf<LyricsProvider>() }
    val providerListState = rememberLazyListState()
    val providerReorderState = rememberReorderableLazyListState(lazyListState = providerListState) { from, to ->
        mutableProviders.move(from.index, to.index)
    }

    fun resolvedOrder(stored: String) =
        orderProviders(REMOTE_LYRICS_PROVIDERS, stored.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    fun resetProviderList(stored: String) {
        mutableProviders.clear()
        mutableProviders.addAll(resolvedOrder(stored))
    }

    LaunchedEffect(providerOrder) { resetProviderList(providerOrder) }

    SwitchPreference(
        title = { Text(stringResource(R.string.enable_simpmusic)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableSimpMusic,
        onCheckedChange = onEnableSimpMusicChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.enable_betterlyrics)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableBetterLyrics,
        onCheckedChange = onEnableBetterLyricsChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.enable_lrclib)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableLrcLib,
        onCheckedChange = onEnableLrcLibChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.enable_kugou)) },
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enableKugou,
        onCheckedChange = onEnableKugouChange
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.lyrics_fetch_mode_title)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
        selectedValue = fetchMode,
        onValueSelected = onFetchModeChange,
        values = LyricsFetchMode.entries,
        valueText = {
            when (it) {
                LyricsFetchMode.AUTO -> stringResource(R.string.lyrics_fetch_mode_auto)
                LyricsFetchMode.MANUAL -> stringResource(R.string.lyrics_fetch_mode_manual)
            }
        }
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.lyrics_provider_order_title)) },
        description = resolvedOrder(providerOrder).joinToString(", ") { it.name },
        icon = { Icon(Icons.Rounded.Reorder, null) },
        onClick = { showProviderOrder = true }
    )
    // prioritize local lyric files over all cloud providers
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_prefer_local)) },
        description = stringResource(R.string.lyrics_prefer_local_description),
        icon = { Icon(Icons.Rounded.ContentCut, null) },
        checked = preferLocalLyric,
        onCheckedChange = onPreferLocalLyric
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.lyrics_prefetch_title)) },
        description = stringResource(R.string.lyrics_prefetch_description),
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = enablePrefetch,
        onCheckedChange = onEnablePrefetchChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.lyrics_prefetch_count_title)) },
        description = prefetchCount.toString(),
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        isEnabled = enablePrefetch,
        onClick = { showPrefetchCountDialog = true }
    )

    if (showProviderOrder) {
        ActionPromptDialog(
            title = stringResource(R.string.lyrics_provider_order_title),
            onDismiss = { showProviderOrder = false },
            onConfirm = {
                onProviderOrderChange(mutableProviders.joinToString(",") { it.id })
                showProviderOrder = false
            },
            onReset = {
                onProviderOrderChange("")
                resetProviderList("")
            },
            onCancel = {
                resetProviderList(providerOrder)
                showProviderOrder = false
            }
        ) {
            LazyColumn(
                state = providerListState,
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(ThumbnailCornerRadius)
                    )
            ) {
                itemsIndexed(
                    items = mutableProviders,
                    key = { _, provider -> provider.id }
                ) { _, provider ->
                    ReorderableItem(
                        state = providerReorderState,
                        key = provider.id
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                                .fillMaxWidth()
                        ) {
                            Text(text = provider.name)
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.draggableHandle()
                            )
                        }
                    }
                }
            }

            InfoLabel(
                stringResource(
                    if (fetchMode == LyricsFetchMode.MANUAL) {
                        R.string.lyrics_provider_order_description
                    } else {
                        R.string.lyrics_provider_order_auto_note
                    }
                )
            )
        }
    }

    if (showPrefetchCountDialog) {
        CounterDialog(
            title = stringResource(R.string.lyrics_prefetch_count_title),
            initialValue = prefetchCount,
            upperBound = 10,
            lowerBound = 1,
            unitDisplay = "",
            onDismiss = { showPrefetchCountDialog = false },
            onConfirm = {
                onPrefetchCountChange(it)
                showPrefetchCountDialog = false
            },
            onReset = { onPrefetchCountChange(3) },
            onCancel = { showPrefetchCountDialog = false }
        )
    }
}

@Composable
fun ColumnScope.LyricAdvancedFrag() {
    val (lyricUpdateSpeed, onLyricsUpdateSpeedChange) = rememberEnumPreference(LyricUpdateSpeed, Speed.MEDIUM)
    val (lyricsFancy, onLyricsFancyChange) = rememberPreference(LyricKaraokeEnable, false)
    val (lyricOffset, onLyricOffsetChange) = rememberPreference(LyricOffsetKey, 0)
    var showOffsetDialog by remember { mutableStateOf(false) }
    val (estimatedWordSync, onEstimatedWordSyncChange) = rememberPreference(LyricEstimatedWordSync, false)
    val (syncedLyricsClickable, onSyncedLyricsClickable) = rememberPreference(LyricClickable, defaultValue = true)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        // clickable lyrics
        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_synced_clickable)) },
            icon = { Icon(Icons.Rounded.TouchApp, null) },
            checked = syncedLyricsClickable,
            onCheckedChange = onSyncedLyricsClickable
        )
    }
    Spacer(modifier = Modifier.height(16.dp))

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_karaoke_title)) },
            description = stringResource(R.string.lyrics_karaoke_description),
            icon = { Icon(Icons.Rounded.TextRotationAngledown, null) },
            checked = lyricsFancy,
            onCheckedChange = onLyricsFancyChange
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.lyrics_offset_title)) },
            description = stringResource(R.string.lyrics_offset_description, lyricOffset),
            icon = { Icon(Icons.Rounded.Timer, null) },
            onClick = { showOffsetDialog = true },
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_estimated_word_sync_title)) },
            description = stringResource(R.string.lyrics_estimated_word_sync_description),
            icon = { Icon(Icons.Rounded.Timeline, null) },
            checked = estimatedWordSync,
            onCheckedChange = onEstimatedWordSyncChange,
            // Only supplies the timings the karaoke sweep consumes, so it does nothing on its own.
            isEnabled = lyricsFancy
        )

        ListPreference(
            title = { Text(stringResource(R.string.lyrics_karaoke_hz_title)) },
            icon = { Icon(Icons.Rounded.Speed, null) },
            selectedValue = lyricUpdateSpeed,
            onValueSelected = onLyricsUpdateSpeedChange,
            values = Speed.entries,
            valueText = {
                when (it) {
                    Speed.SLOW -> stringResource(R.string.speed_slow)
                    Speed.MEDIUM -> stringResource(R.string.speed_medium)
                    Speed.FAST -> stringResource(R.string.speed_fast)
                }
            },
            isEnabled = lyricsFancy
        )
    }

    if (showOffsetDialog) {
        CounterDialog(
            title = stringResource(R.string.lyrics_offset_title),
            initialValue = lyricOffset,
            // Five seconds either way. Wider than that is not a sync correction, it is the wrong
            // lyrics file.
            upperBound = 5000,
            lowerBound = -5000,
            unitDisplay = " ms",
            onDismiss = { showOffsetDialog = false },
            onConfirm = { onLyricOffsetChange(it); showOffsetDialog = false },
            onReset = { onLyricOffsetChange(0) },
            onCancel = { showOffsetDialog = false },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LyricFormatFragPreview() {
    Column {
        LyricFormatFrag()
    }
}

@Preview(showBackground = true)
@Composable
private fun LyricParserFragPreview() {
    Column {
        LyricParserFrag()
    }
}

@Preview(showBackground = true)
@Composable
private fun LyricSourceFragPreview() {
    Column {
        LyricSourceFrag()
    }
}

@Preview(showBackground = true)
@Composable
private fun LyricAdvancedFragPreview() {
    Column {
        LyricAdvancedFrag()
    }
}