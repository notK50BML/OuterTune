/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.player.BandStrip
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.audio.EqualizerAudioProcessor
import com.dd3boh.outertune.constants.EqHeadroom
import com.dd3boh.outertune.constants.EqHeadroomKey
import com.dd3boh.outertune.constants.EqUseDialsKey
import com.dd3boh.outertune.constants.EqValueColorGradientKey
import com.dd3boh.outertune.constants.EqualizerProfilesKey
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.EqualizerProfile
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.component.EqResponseCurve
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.player.BandColumn
import com.dd3boh.outertune.ui.player.BandEditor
import com.dd3boh.outertune.ui.player.CompressorSection
import com.dd3boh.outertune.ui.player.EqualizerCard
import com.dd3boh.outertune.ui.player.ToneControlsRow
import com.dd3boh.outertune.ui.player.quantizeTenth
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay

/**
 * Equaliser Studio: tune a curve against one song, on a loop, without touching anything else.
 *
 * It plays through a private player (see MusicService.createStudioPlayer) with its own equaliser,
 * so the main player's sound, queue, history and stats are left exactly as they were - the main
 * player is only paused while the Studio plays. Nothing here is applied until it is saved as a
 * profile or applied to the player on purpose.
 *
 * The song loops, all of it or a marked stretch, and "Original" switches the curve out in place so
 * the difference can be heard on the same bar of music - which is the whole point of tuning against
 * one song rather than whatever happens to come on next.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerStudioScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val colour = MaterialTheme.colorScheme.onSurface
    val colourVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val useDials by rememberPreference(EqUseDialsKey, defaultValue = true)
    val useGradient by rememberPreference(EqValueColorGradientKey, defaultValue = false)
    val (headroom, _) = rememberEnumPreference(EqHeadroomKey, defaultValue = EqHeadroom.AUTO)
    var globalJson by rememberPreference(EqualizerSettingsKey, "")
    var profilesJson by rememberPreference(EqualizerProfilesKey, "")
    val savedProfiles = remember(profilesJson) { EqualizerProfile.listFromJson(profilesJson) }

    // The curve being worked on. Starts as whatever the player uses now, and is never written
    // anywhere until Save or Apply.
    var settings by remember {
        mutableStateOf(EqualizerSettings.parse(globalJson).getOrDefault(EqualizerSettings.DEFAULT).copy(enabled = true))
    }
    var original by rememberSaveable { mutableStateOf(false) }
    var selectedBand by remember { mutableStateOf<Int?>(null) }

    var songId by rememberSaveable { mutableStateOf<String?>(null) }
    val song by remember(songId) { database.song(songId) }.collectAsState(initial = null)
    var picking by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }

    // ---- the private player ----------------------------------------------------------------
    val processor = remember { EqualizerAudioProcessor() }
    val player = remember(playerConnection) { playerConnection?.service?.createStudioPlayer(processor) }
    DisposableEffect(player) { onDispose { player?.release() } }

    LaunchedEffect(headroom) {
        processor.setSmartHeadroom(headroom.smart)
        processor.setHeadroom(headroom.factor)
    }
    LaunchedEffect(settings, original) {
        processor.setSettings(settings.copy(enabled = !original))
    }

    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var loopStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var loopEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(song?.id) {
        val current = song ?: return@LaunchedEffect
        player ?: return@LaunchedEffect
        loopStart = null
        loopEnd = null
        player.setMediaItem(current.toMediaItem())
        player.prepare()
    }
    // Polled rather than listened to: the readout wants a steady tick anyway, and the marked-loop
    // check has to run on the clock, not on events.
    LaunchedEffect(player) {
        player ?: return@LaunchedEffect
        while (true) {
            playing = player.isPlaying
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            val a = loopStart
            val b = loopEnd
            if (a != null && b != null && b > a && position >= b) player.seekTo(a)
            delay(100)
        }
    }

    fun play() {
        player ?: return
        // The main player stops for the Studio and stays stopped: resuming it behind the person's
        // back when they leave would be a surprise, and they are one tap from doing it themselves.
        playerConnection?.player?.pause()
        player.play()
    }

    fun loadCurve(curve: EqualizerSettings) {
        settings = curve.copy(enabled = true, compressor = settings.compressor)
        selectedBand = null
    }

    // Padded by the player-aware insets like every other settings page: clear of the top bar, the
    // mini player and the bottom tabs. A fixed top padding left Save and the buttons beside it
    // hidden behind the navigation bar.
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- the song ----------------------------------------------------------------------
        EqualizerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val current = song
                if (current != null) {
                    AsyncImage(
                        model = current.song.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            current.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = colourVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.eq_studio_no_song),
                        color = colourVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.eq_studio_choose_song)) }
            }

            if (song != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { if (playing) player?.pause() else play() }) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(makeTimeString(scrubbing?.toLong() ?: position), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = (scrubbing ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { scrubbing = it },
                        onValueChangeFinished = {
                            scrubbing?.let { player?.seekTo(it.toLong()) }
                            scrubbing = null
                        },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(makeTimeString(duration), style = MaterialTheme.typography.labelMedium)
                }
                // A marked stretch to loop - the chorus, the bass drop - so a change can be judged
                // against the same few seconds every time rather than against a song moving on.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = loopStart != null,
                        onClick = { loopStart = position },
                        label = { Text(loopStart?.let { "A " + makeTimeString(it) } ?: stringResource(R.string.eq_studio_mark_a)) },
                    )
                    FilterChip(
                        selected = loopEnd != null,
                        onClick = { loopEnd = position },
                        label = { Text(loopEnd?.let { "B " + makeTimeString(it) } ?: stringResource(R.string.eq_studio_mark_b)) },
                    )
                    if (loopStart != null || loopEnd != null) {
                        TextButton(onClick = { loopStart = null; loopEnd = null }) { Text(stringResource(R.string.eq_studio_clear_loop)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !original, onClick = { original = false }, label = { Text(stringResource(R.string.eq_studio_tuned)) })
                    FilterChip(selected = original, onClick = { original = true }, label = { Text(stringResource(R.string.eq_studio_original)) })
                }
            }
        }

        // ---- the curve ---------------------------------------------------------------------
        EqualizerCard {
            Text(stringResource(R.string.eq_studio_start_from), style = MaterialTheme.typography.labelLarge, color = colourVariant)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = false,
                        onClick = { loadCurve(EqualizerSettings.parse(globalJson).getOrDefault(EqualizerSettings.DEFAULT)) },
                        label = { Text(stringResource(R.string.eq_studio_current)) },
                    )
                }
                items(EqualizerSettings.PRESETS.keys.toList()) { name ->
                    FilterChip(
                        selected = false,
                        onClick = { EqualizerProfile.factoryDefault(name)?.let { loadCurve(it.settings) } },
                        label = { Text(name) },
                    )
                }
                items(savedProfiles.filter { it.name !in EqualizerSettings.PRESETS.keys }) { profile ->
                    FilterChip(selected = false, onClick = { loadCurve(profile.settings) }, label = { Text(profile.name) })
                }
            }
            Spacer(Modifier.height(12.dp))
            BandStrip(settings.bands.size) { index, modifier ->
                    val band = settings.bands[index]
                    BandColumn(
                        band = band,
                        enabled = true,
                        selected = selectedBand == index,
                        color = colour,
                        useGradient = useGradient,
                        onGainChange = { gain ->
                            settings = settings.copy(
                                bands = settings.bands.toMutableList().also { it[index] = band.copy(gainDb = quantizeTenth(gain)) }
                            )
                        },
                        onTapLabel = { selectedBand = if (selectedBand == index) null else index },
                        modifier = modifier,
                    )
            }
            selectedBand?.let { index ->
                HorizontalDivider(color = colourVariant.copy(alpha = 0.3f))
                BandEditor(
                    band = settings.bands[index],
                    onChange = { changed ->
                        settings = settings.copy(bands = settings.bands.toMutableList().also { it[index] = changed })
                    },
                    color = colour,
                    colorVariant = colourVariant,
                    useDials = useDials,
                    useGradient = useGradient,
                )
            }
            Spacer(Modifier.height(12.dp))
            EqResponseCurve(
                bands = settings.allFilters(),
                sampleRate = 44_100,
                lineColor = colour,
                fillColor = colour,
                gridColor = colourVariant,
                colorByHeight = useGradient,
            )
        }

        EqualizerCard {
            ToneControlsRow(
                bassGainDb = settings.bassShelfDb,
                trebleGainDb = settings.trebleShelfDb,
                balance = settings.balance,
                enabled = true,
                color = colour,
                useGradient = useGradient,
                onBassChange = { settings = settings.copy(bassShelfDb = quantizeTenth(it)) },
                onTrebleChange = { settings = settings.copy(trebleShelfDb = quantizeTenth(it)) },
                onBalanceChange = { settings = settings.copy(balance = it) },
            )
        }

        EqualizerCard {
            CompressorSection(
                compressor = settings.compressor,
                onChange = { settings = settings.copy(compressor = it) },
                color = colour,
                colorVariant = colourVariant,
                useDials = useDials,
                useGradient = useGradient,
            )
        }

        // ---- keeping it ---------------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { naming = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.eq_studio_save_profile))
            }
            Button(
                onClick = {
                    val applied = settings.copy(enabled = true)
                    globalJson = applied.toJson()
                    playerConnection?.service?.equalizerAudioProcessor?.setSettings(applied)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.eq_studio_apply))
            }
        }
        Text(
            stringResource(R.string.eq_studio_note),
            style = MaterialTheme.typography.bodySmall,
            color = colourVariant,
        )
    }

    if (naming) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.eq_settings_save_as_profile)) },
            placeholder = { Text(stringResource(R.string.eq_settings_profile_name)) },
            isInputValid = { it.isNotBlank() },
            onDone = { name ->
                val trimmed = name.trim()
                profilesJson = EqualizerProfile.listToJson(
                    savedProfiles.filterNot { it.name == trimmed } + EqualizerProfile(trimmed, settings)
                )
            },
            onDismiss = { naming = false },
        )
    }

    if (picking) {
        SongPicker(
            search = { query -> database.searchSongsInDb(query, 60) },
            onPick = {
                songId = it.id
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.eq_studio_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

/** Any song the app knows, searched by title. */
@Composable
private fun SongPicker(
    search: (String) -> kotlinx.coroutines.flow.Flow<List<Song>>,
    onPick: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results by remember(query) { search(query.trim()) }.collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.eq_studio_choose_song)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(results, key = { it.id }) { song ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(song) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            AsyncImage(
                                model = song.song.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    song.artists.joinToString { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
