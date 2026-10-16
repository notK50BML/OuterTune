/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.EqUseDialsKey
import com.dd3boh.outertune.constants.EqValueColorGradientKey
import kotlinx.coroutines.delay
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.SongEqualizerProfilesKey
import com.dd3boh.outertune.constants.EqAutoSaveProfilesKey
import com.dd3boh.outertune.constants.EqShowPanelSettingsKey
import com.dd3boh.outertune.constants.EqualizerProfilesKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.models.EqualizerProfile
import com.dd3boh.outertune.models.SongEqualizerProfiles
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.component.PowerampThumb
import com.dd3boh.outertune.ui.component.PowerampTrack
import com.dd3boh.outertune.ui.component.RotaryDial
import androidx.compose.ui.draw.alpha
import com.dd3boh.outertune.ui.component.EqResponseCurve
import com.dd3boh.outertune.ui.component.TransportSize
import com.dd3boh.outertune.ui.component.VerticalSlider
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Width given to each band's column in the horizontally-scrolling strip, Poweramp-style. */
private val BandColumnWidth = 56.dp

/**
 * The style every panel heading uses.
 *
 * Named rather than written out three times, because the three of them being identical is the
 * point: "Equalizer", "Compressor" and "Additional settings" are peers. The compressor's was
 * labelMedium while the equaliser's was titleLarge, which made one of three sibling panels read as
 * a sub-heading of whatever happened to be above it.
 */
private val PanelTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge

/** How long the controls must settle before an auto-saved profile is written. */
private const val PROFILE_AUTOSAVE_SETTLE_MS = 500L

/**
 * Sample rate the response curve is drawn at.
 *
 * Fixed rather than read from the player, because the graph has to be there before anything is
 * playing and must not change shape when the next track happens to be 48kHz. 44.1kHz puts Nyquist
 * at 22.05kHz, which is past the top of the plot, so the drawn range is identical either way - the
 * only thing this changes is a region nobody can see.
 */
private const val EQ_GRAPH_SAMPLE_RATE = 44_100

/** How much of the panel's top the drag handle claims - bigger than a slim strip, but this one is
 *  for the panel that's already open; the small pull-down on the player screen that opens it in
 *  the first place is the one that actually needed to grow. */
private val EqualizerHandleHeight = 56.dp

/**
 * One section of the panel (top settings, tone controls/profiles, frequency bands, compressor),
 * set apart as its own rounded card rather than divided from its neighbours by a thin line -
 * against a busy Liquid/Blur background especially, a hairline divider is easy to miss while a
 * whole panel is not. Translucent rather than a flat opaque surface, so the panel still reads as
 * floating over the player background instead of paving over it entirely.
 */
@Composable
internal fun EqualizerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .padding(16.dp),
        content = content,
    )
}

/**
 * A 12-band parametric equalizer, backed by [com.dd3boh.outertune.audio.EqualizerAudioProcessor]
 * in the playback audio pipeline.
 *
 * Full-screen rather than a sheet, opened from the pull-down handle above the cover art or the
 * button beside the like button (both reading [LocalEqualizerPanelState], which is what makes this
 * survive a track change - the state lives above the player content, not inside the transient
 * 3-dot menu popup the first version of this was nested in). The gain strip is the everyday view;
 * tapping a band's frequency label opens an advanced editor underneath for its frequency, Q and
 * filter type - a graphic EQ most of the time, a parametric one when a band needs it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerPanel() {
    val panelState = LocalEqualizerPanelState.current

    AnimatedVisibility(
        visible = panelState.visible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
    ) {
        EqualizerPanelContent(onDismiss = { panelState.visible = false })
    }
}

/**
 * How long the controls must sit still before a song override is written.
 *
 * Long enough that a drag is one write rather than sixty, short enough that closing the panel
 * straight after an adjustment still catches it.
 */
private const val SONG_PROFILE_SETTLE_MS = 400L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerPanelContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )
    val handleColor = rememberPlayerOnBackgroundColor(mediaMetadata, playerBackground)

    // Whether to color the panel's own text/icons/dials/sliders (everything that isn't already a
    // Material3-themed widget like Switch/SegmentedButton, which already follows the app theme
    // correctly) from the cover art's contrast color instead of the plain theme color - on by
    // default since a busy or dark cover can otherwise swallow plain onSurface text.
    // Was a "Use cover contrast colour" switch, tinting the panel from the artwork instead of the
    // theme. It never earned its place: the panel's cards are a flat dark grey whatever the artwork
    // is, so the two settings looked near enough identical, and a toggle whose effect cannot be
    // seen is worse than no toggle. Fixed to the theme colour, which is what it was being left on.
    val eqColor = MaterialTheme.colorScheme.onSurface
    val eqColorVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Dials vs sliders, and whether either one colors itself by its own value instead of by the
    // contrast/theme color above - both apply everywhere a band gain, frequency, bass/treble, or
    // compressor control appears, so there's one place that decides "how does this look" rather
    // than each control guessing independently.
    var useDials by rememberPreference(EqUseDialsKey, defaultValue = true)
    var useValueGradient by rememberPreference(EqValueColorGradientKey, defaultValue = false)

    BackHandler(onBack = onDismiss)

    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }
    val showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    var settingsJson by rememberPreference(EqualizerSettingsKey, "")

    // Per-song overrides. The service applies whichever is in force (see MusicService); this is
    // the half that edits them, and which one an edit lands in is decided by boundToSong below.
    var songProfilesJson by rememberPreference(SongEqualizerProfilesKey, "")
    val songProfiles = remember(songProfilesJson) { SongEqualizerProfiles.fromJson(songProfilesJson) }
    val currentSongId = mediaMetadata?.id
    val boundToSong = currentSongId != null && currentSongId in songProfiles
    val latestProfiles by rememberUpdatedState(songProfiles)

    /**
     * The chip standing for the song's own curve.
     *
     * Only in the row while the per-song switch is on, because that is the only time it refers to
     * anything: it is not a stored profile with a name of its own, it is a label for "whatever is
     * playing". It has no delete button for the same reason - the switch is how it goes away.
     *
     * Declared up here rather than beside the other profile state because [bindToSong] below needs
     * it, and a local cannot be used before it exists.
     */
    val currentSongProfile = stringResource(R.string.eq_current_song_profile)
    var activeProfileName by remember { mutableStateOf<String?>(null) }

    val globalSettings = remember(settingsJson) {
        EqualizerSettings.parse(settingsJson).getOrDefault(EqualizerSettings.DEFAULT)
    }

    var settings by remember {
        mutableStateOf(songProfiles[currentSongId]?.settings ?: globalSettings)
    }

    // Re-seeded on a track change only. Keyed on the song rather than on the stored strings
    // because this writes those strings: keying on them would reset the panel to its own last
    // saved value halfway through a drag, every drag.
    LaunchedEffect(currentSongId) {
        settings = latestProfiles[currentSongId]?.settings ?: globalSettings
        playerConnection.service.equalizerAudioProcessor.setSettings(settings)
    }

    // A song override is one preference string holding every override there is, so writing it on
    // every frame of a slider drag would be a six-figure-byte disk write per frame. The audio
    // follows the slider immediately; the stored copy catches up once it stops.
    var pendingSongWrite by remember { mutableStateOf<EqualizerSettings?>(null) }
    LaunchedEffect(pendingSongWrite, currentSongId) {
        val toWrite = pendingSongWrite ?: return@LaunchedEffect
        val songId = currentSongId ?: return@LaunchedEffect
        delay(SONG_PROFILE_SETTLE_MS)
        songProfilesJson = SongEqualizerProfiles.toJson(
            SongEqualizerProfiles.put(
                latestProfiles, songId, toWrite, System.currentTimeMillis()
            ).values
        )
        pendingSongWrite = null
    }

    fun update(newSettings: EqualizerSettings) {
        settings = newSettings
        playerConnection.service.equalizerAudioProcessor.setSettings(newSettings)
        // Edits go wherever the curve on screen came from. The alternative - always writing the
        // global one - would mean that with an override active, every adjustment was silently
        // discarded the next time the song came round, which is the worst of both.
        if (boundToSong) pendingSongWrite = newSettings else settingsJson = newSettings.toJson()
    }

    /** Starts or stops this song having a curve of its own. */
    fun bindToSong(bind: Boolean) {
        val songId = currentSongId ?: return
        // The chip follows the switch in both directions: on, the song's own curve is what is
        // selected; off, nothing is, because what is on screen is the global curve and no profile
        // claims it. Leaving a stale name selected would point Save at a profile the panel is no
        // longer showing.
        activeProfileName = if (bind) currentSongProfile else null
        songProfilesJson = if (bind) {
            // Seeded from whatever is on screen, so turning this on keeps the sound it was turned
            // on with rather than snapping to a default and making the user dial it in twice.
            SongEqualizerProfiles.toJson(
                SongEqualizerProfiles.put(
                    latestProfiles, songId, settings, System.currentTimeMillis()
                ).values
            )
        } else {
            pendingSongWrite = null
            // Back to the global curve, applied now rather than at the next track change, so the
            // switch is heard as well as seen.
            settings = globalSettings
            playerConnection.service.equalizerAudioProcessor.setSettings(globalSettings)
            SongEqualizerProfiles.toJson(SongEqualizerProfiles.remove(latestProfiles, songId).values)
        }
    }

    fun updateBand(index: Int, band: EqualizerSettings.EqBand) {
        update(settings.copy(bands = settings.bands.toMutableList().apply { this[index] = band }))
    }

    // A preset name doubles as a profile name: built-ins get a factory curve from PRESETS
    // ([EqualizerProfile.factoryDefault]) plus, once Saved at least once, a custom override in
    // this list; a user-created name only ever has the latter. Selecting a name loads whichever
    // of the two exists (override first), so a tweak made while a preset is active has somewhere
    // to persist instead of being silently lost the next time that preset is tapped again.
    var profilesJson by rememberPreference(EqualizerProfilesKey, "")
    val savedProfiles = remember(profilesJson) { EqualizerProfile.listFromJson(profilesJson) }
    val builtInProfileNames = remember { EqualizerSettings.PRESETS.keys.toList() }
    val profileNames = remember(savedProfiles, builtInProfileNames, boundToSong, currentSongProfile) {
        val saved = savedProfiles.map { it.name }.filter { it !in builtInProfileNames }
        // First in the row when it is there at all, since it is the most specific thing on offer.
        if (boundToSong) listOf(currentSongProfile) + builtInProfileNames + saved
        else builtInProfileNames + saved
    }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    /** Whether the two display settings appear here as well as in Settings > Equalizer. */
    val showPanelDisplaySettings by rememberPreference(EqShowPanelSettingsKey, defaultValue = false)
    val autoSaveProfiles by rememberPreference(EqAutoSaveProfilesKey, defaultValue = false)

    fun savedProfile(name: String) = savedProfiles.find { it.name == name }

    /**
     * Writes edits straight into the selected profile, when that is asked for.
     *
     * Off by default: Save existing as a deliberate act is the safer arrangement, and silently
     * rewriting a preset somebody is only auditioning would be a surprise. On, it removes the step
     * that is easy to forget - adjusting a profile, switching away, and finding the adjustment
     * gone.
     *
     * Settled rather than immediate, like every other write in this panel: one frame of a slider
     * drag would otherwise be a disk write.
     *
     * The per-song curve is excluded because it already saves itself - that is what the switch
     * means - and writing it into a named profile as well would put the same edit in two places.
     */
    if (autoSaveProfiles && !boundToSong) {
        val autoSaveTarget = activeProfileName
        LaunchedEffect(settings, autoSaveTarget) {
            if (autoSaveTarget == null) return@LaunchedEffect
            delay(PROFILE_AUTOSAVE_SETTLE_MS)
            val updated = savedProfiles.filterNot { it.name == autoSaveTarget } +
                EqualizerProfile(autoSaveTarget, settings)
            profilesJson = EqualizerProfile.listToJson(updated)
        }
    }

    fun loadProfile(name: String) {
        // Selecting the song's own chip is already what is on screen - it is a label for the
        // current state, not something to load over it.
        if (name == currentSongProfile) {
            activeProfileName = name
            return
        }
        // Choosing any other curve means this song is no longer on one of its own. Turning the
        // switch off here rather than leaving both lit is what stops the panel claiming the song
        // has a private curve while plainly showing a preset.
        if (boundToSong) bindToSong(false)

        val saved = savedProfile(name)
        val toApply = saved?.settings ?: EqualizerProfile.factoryDefault(name)?.settings ?: return
        // A built-in preset's factory curve carries no compressor settings of its own (only band
        // gains), so applying one for the first time (nothing Saved under this name yet) must keep
        // whatever compressor the user already has dialed in rather than silently reverting it to
        // default. A saved profile's own compressor - the whole reason Save exists - still wins.
        val compressorToApply = if (saved != null) toApply.compressor else settings.compressor
        update(toApply.copy(enabled = settings.enabled, compressor = compressorToApply))
        activeProfileName = name
    }

    fun saveProfile(name: String) {
        val newList = savedProfiles.filterNot { it.name == name } + EqualizerProfile(name, settings)
        profilesJson = EqualizerProfile.listToJson(newList)
        activeProfileName = name
    }

    fun deleteProfile(name: String) {
        profilesJson = EqualizerProfile.listToJson(savedProfiles.filterNot { it.name == name })
        if (activeProfileName == name) activeProfileName = null
    }

    var selectedBand by remember { mutableStateOf<Int?>(null) }
    var importExportError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(settings.toJson().toByteArray()) }
        }.onFailure { importExportError = it.message }
    }

    // OpenDocument rather than GetContent: this needs a real, re-readable uri, not a one-shot
    // content stream.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            importExportError = "Could not read that file."
            return@rememberLauncherForActivityResult
        }
        EqualizerSettings.parse(text)
            .onSuccess {
                update(it)
                importExportError = null
            }
            .onFailure { importExportError = it.message ?: "That file isn't a valid equalizer profile." }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // This panel sits on top of the player's own draggable BottomSheet, but only
            // EqualizerPanelHandle's strip used to intercept drags - everywhere else (padding,
            // labels, the space between controls) let a vertical drag fall straight through to
            // the sheet underneath, which read as "dragging inside the EQ panel minimises the
            // player to the mini player". Swallowing unclaimed vertical drags here stops that:
            // anything that actually wants the gesture (the scrollable column, a slider, a dial,
            // the handle itself) is deeper in the tree and consumes it first, so this only catches
            // drags nothing else wanted.
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ -> change.consume() }
            }
    ) {
        PlayerBackground(
            playerConnection = playerConnection,
            playerBackground = playerBackground,
            showLyrics = showLyrics,
            useDarkTheme = useDarkTheme,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            EqualizerPanelHandle(onDismiss = onDismiss, handleColor = eqColor)

            Column(
                modifier = Modifier
                    // fill = true, which is the default and which this used to opt out of. With
                    // fill = false the scrolling area shrinks to its content, so the transport bar
                    // below it is only at the bottom of the screen while the content is tall
                    // enough to push it there - and it slid up the moment both panels were
                    // collapsed. It is a docked bar; it does not move.
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // "Bass"/"Treble" move the whole low/high end together, like a tone control on a
                // real amp, rather than just nudging the single lowest/highest band - anything at
                // or below 250Hz counts as bass and anything at or above 4kHz counts as treble,
                // the same split VisualizerFrame already uses elsewhere in this app.
                val bassBandIndices = settings.bands.indices.filter { settings.bands[it].freqHz <= 250f }
                val trebleBandIndices = settings.bands.indices.filter { settings.bands[it].freqHz >= 4000f }
                val bassGainDb = bassBandIndices.map { settings.bands[it].gainDb }.average().toFloat()
                val trebleGainDb = trebleBandIndices.map { settings.bands[it].gainDb }.average().toFloat()

                fun setRangeGain(indices: List<Int>, newGain: Float) {
                    val quantized = quantizeTenth(newGain)
                    update(settings.copy(bands = settings.bands.mapIndexed { i, b ->
                        if (i in indices) b.copy(gainDb = quantized) else b
                    }))
                }

                // ---- Equaliser -------------------------------------------------------------
                //
                // Everything the equaliser itself is, in the order it is read: what it is called
                // and whether it is on, then the curves you can load, then the controls that make
                // one, then the picture of the result, then what it applies to.
                //
                // Switching it off collapses the whole panel rather than dimming it, which is what
                // the compressor already did and is the behaviour worth having in both: an
                // equaliser that is off has nothing to say, and leaving twelve dead faders on
                // screen makes the panel look broken rather than disabled.
                EqualizerCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.equalizer),
                            style = PanelTitleStyle,
                            color = eqColor,
                        )
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { update(settings.copy(enabled = it)) },
                        )
                    }

                    if (settings.enabled) {
                        Spacer(Modifier.height(12.dp))

                        // Every curve that can be loaded, in one row: the built-in presets, the
                        // profiles saved here, and - only while it is switched on - the current
                        // song's own. No heading above it; the card is already called Equalizer
                        // and a second label under it would be saying so twice.
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(profileNames) { name ->
                                FilterChip(
                                    selected = activeProfileName == name,
                                    onClick = { loadProfile(name) },
                                    label = { Text(name) },
                                    trailingIcon = if (name !in builtInProfileNames && name != currentSongProfile) {
                                        {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { deleteProfile(name) },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        labelColor = eqColorVariant,
                                        iconColor = eqColorVariant,
                                        selectedContainerColor = eqColor.copy(alpha = 0.2f),
                                        selectedLabelColor = eqColor,
                                        selectedLeadingIconColor = eqColor,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = activeProfileName == name,
                                        borderColor = eqColorVariant.copy(alpha = 0.4f),
                                        selectedBorderColor = eqColor,
                                    ),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // A plain Row of 12 columns squeezes every band into whatever width the
                        // screen has; a LazyRow at a fixed per-band width instead gives each one
                        // real room to drag in and lets the strip run wider than the screen,
                        // Poweramp-style, with the rest reached by scrolling sideways.
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        ) {
                            itemsIndexed(settings.bands) { index, band ->
                                BandColumn(
                                    band = band,
                                    enabled = settings.enabled,
                                    selected = selectedBand == index,
                                    color = eqColor,
                                    useGradient = useValueGradient,
                                    onGainChange = { updateBand(index, band.copy(gainDb = quantizeTenth(it))) },
                                    onTapLabel = { selectedBand = if (selectedBand == index) null else index },
                                )
                            }
                        }

                        // Directly under the band it belongs to, so the thing being edited and the
                        // editor are never separated by anything else.
                        selectedBand?.let { index ->
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = eqColorVariant.copy(alpha = 0.3f),
                            )
                            BandEditor(
                                band = settings.bands[index],
                                onChange = { updateBand(index, it) },
                                color = eqColor,
                                colorVariant = eqColorVariant,
                                useDials = useDials,
                                useGradient = useValueGradient,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Below the faders, because it is what they add up to and reads as their
                        // result rather than as a second control. Twelve slider positions do not
                        // say what the filter bank is doing: the bands overlap, so two adjacent
                        // boosts make one larger hump rather than two small ones, and nothing in a
                        // row of faders can show that.
                        EqResponseCurve(
                            bands = settings.bands,
                            sampleRate = EQ_GRAPH_SAMPLE_RATE,
                            // The panel's own contrast colour, not a fixed green. With
                            // colour-by-value off every other control here falls back to this, and
                            // the curve staying green made the setting look like it had missed
                            // something rather than been applied.
                            lineColor = eqColor,
                            fillColor = eqColor,
                            gridColor = eqColorVariant,
                            colorByHeight = useValueGradient,
                        )

                        // Last, because it answers "what does all of the above apply to" - which
                        // is only worth asking once the above has been read.
                        mediaMetadata?.let { nowPlaying ->
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.eq_song_profile),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = eqColor,
                                    )
                                    Text(
                                        text = if (boundToSong) {
                                            stringResource(R.string.eq_song_profile_on, nowPlaying.title)
                                        } else {
                                            stringResource(R.string.eq_song_profile_off)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = eqColorVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = boundToSong,
                                    onCheckedChange = { bindToSong(it) },
                                )
                            }
                        }
                    }
                }

                // ---- Compressor ------------------------------------------------------------
                EqualizerCard {
                    CompressorSection(
                        compressor = settings.compressor,
                        onChange = { update(settings.copy(compressor = it)) },
                        color = eqColor,
                        colorVariant = eqColorVariant,
                        useDials = useDials,
                        useGradient = useValueGradient,
                    )
                }

                // ---- Additional settings ---------------------------------------------------
                //
                // What is left once the equaliser and the compressor have taken what is theirs:
                // the controls that shape a curve without being one, and everything to do with
                // keeping it.
                EqualizerCard {
                    Text(
                        text = stringResource(R.string.eq_additional_settings),
                        style = PanelTitleStyle,
                        color = eqColor,
                    )

                    Spacer(Modifier.height(12.dp))

                    ToneControlsRow(
                        bassGainDb = bassGainDb,
                        trebleGainDb = trebleGainDb,
                        balance = settings.balance,
                        enabled = settings.enabled,
                        color = eqColor,
                        useGradient = useValueGradient,
                        onBassChange = { setRangeGain(bassBandIndices, it) },
                        onTrebleChange = { setRangeGain(trebleBandIndices, it) },
                        onBalanceChange = { update(settings.copy(balance = it)) },
                    )

                    // The panel's own copy of the two display settings, when asked for. They live
                    // in Settings > Equalizer by default - they are about how this panel looks
                    // rather than about the sound, and a panel that configures itself in the
                    // middle of its own controls is a panel with its settings screen embedded in
                    // it. The option exists because that is occasionally exactly what is wanted.
                    if (showPanelDisplaySettings) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.eq_use_dials),
                                style = MaterialTheme.typography.labelMedium,
                                color = eqColorVariant,
                            )
                            Switch(checked = useDials, onCheckedChange = { useDials = it })
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.eq_colour_by_value),
                                style = MaterialTheme.typography.labelMedium,
                                color = eqColorVariant,
                            )
                            Switch(checked = useValueGradient, onCheckedChange = { useValueGradient = it })
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // One scrolling row rather than three stacked ones. These are six variations
                    // on "keep this curve" and stacking them made a block of buttons taller than
                    // the controls they were about; in a line they read as one group, and the
                    // ones that do not apply yet simply are not in it.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        if (activeProfileName != null) {
                            TextButton(
                                onClick = { activeProfileName?.let(::saveProfile) },
                                colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                            ) {
                                Text(stringResource(R.string.eq_save))
                            }
                            TextButton(
                                onClick = { activeProfileName?.let { loadProfile(it) } },
                                colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                            ) {
                                Text(stringResource(R.string.eq_revert_to_saved))
                            }
                            if (activeProfileName in builtInProfileNames) {
                                TextButton(
                                    onClick = {
                                        activeProfileName?.let { name ->
                                            EqualizerProfile.factoryDefault(name)?.settings?.let {
                                                update(it.copy(enabled = settings.enabled))
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                                ) {
                                    Text(stringResource(R.string.eq_revert_to_default))
                                }
                            }
                        }
                        TextButton(
                            onClick = { showSaveAsDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                        ) {
                            Text(stringResource(R.string.eq_save_as_new))
                        }
                        TextButton(
                            onClick = { exportLauncher.launch("equalizer-profile.json") },
                            colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                        ) {
                            Text(stringResource(R.string.eq_export))
                        }
                        TextButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            colors = ButtonDefaults.textButtonColors(contentColor = eqColorVariant),
                        ) {
                            Text(stringResource(R.string.eq_import))
                        }
                    }

                    importExportError?.let {
                        Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }

                    if (showSaveAsDialog) {
                        TextFieldDialog(
                            title = { Text(stringResource(R.string.eq_save_as_new)) },
                            placeholder = { Text(stringResource(R.string.eq_profile_name)) },
                            isInputValid = { it.isNotBlank() },
                            onDone = { name -> saveProfile(name.trim()) },
                            onDismiss = { showSaveAsDialog = false },
                        )
                    }
                }
            }

            // Docked below the scrollable content, not inside it - the same treatment the queue's
            // own bottom bar gets (secondaryContainer card, safe-area padding), just without a
            // queue-info row since there's no queue here to describe.
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 16.dp)
            ) {
                MiniPlaybackControls(color = eqColor)
            }
        }
    }
}

/** left/right label like "50/50" - a balance of 0 is centered (50/50), +1 is full right (0/100). */
private fun balanceReadout(balance: Float): String {
    val right = ((balance + 1f) / 2f * 100f).roundToInt().coerceIn(0, 100)
    val left = 100 - right
    return "$left/$right"
}

/** Snaps a dial/slider's continuous drag output to a clean 0.1 step, e.g. "3.2" rather than "3.187456". */
internal fun quantizeTenth(value: Float): Float = (value * 10f).roundToInt() / 10f

private fun formatDb(db: Float): String =
    "${if (db > 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", db)}"

/** The slider track's "used" portion - fixed, not value- or contrast-tinted, so every slider in
 *  the panel reads as the same kind of control regardless of the dial/gradient settings. */

/** The track's "not used yet" portion - a light neutral rather than a dim tint of the active
 *  color, so it reads as "empty" against either a light or a dark panel background. */
@Composable
private fun sliderInactiveColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

/**
 * Yellow at the low end of [range], green in the middle, blue at the high end.
 *
 * This used to run the other way round - blue low, yellow high - and the direction was the only
 * thing wrong with it. Turning a control up makes the sound brighter and thinner, which is what
 * blue reads as; turning it down makes it heavier and warmer, which is yellow. Running it the
 * other way meant the colours argued with the thing they were describing.
 *
 * It also put the panel at odds with itself once the response curve arrived, since that one is
 * read by height and nobody would expect a boost to be yellow on the graph and blue on the fader
 * that caused it. The desktop has always run this direction; the phone was the odd one out.
 *
 * One line to put back if the old direction is wanted: swap the two colours below.
 */
private fun valueGradientColor(value: Float, range: ClosedFloatingPointRange<Float>): Color {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val low = Color(0xFFFFD500)
    val middle = Color(0xFF66DD44)
    val high = Color(0xFF2196F3)
    return if (fraction < 0.5f) {
        lerp(low, middle, fraction / 0.5f)
    } else {
        lerp(middle, high, (fraction - 0.5f) / 0.5f)
    }
}

/**
 * A horizontal Poweramp-styled slider with a label above and a value readout below, mirroring
 * [RotaryDial]'s own layout so switching the "use dials" setting doesn't reflow anything around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerampSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    /**
     * The label/value text's color - always the plain contrast color, never the value gradient,
     * since a small readout tinted the same hue as its own track is a legibility risk.
     *
     * The track and thumb follow it too. They used to be a fixed green, which is why turning
     * colour-by-value off left the panel green rather than returning it to the plain contrast
     * colour every other control falls back to.
     */
    textColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    valueLabel: String? = null,
    width: Dp = 140.dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .border(1.dp, textColor.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp)
    ) {
        label?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = textColor.copy(alpha = 0.75f))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.width(width),
            track = { sliderState ->
                PowerampTrack(sliderState = sliderState, activeColor = textColor, inactiveColor = sliderInactiveColor())
            },
            thumb = { PowerampThumb(color = textColor) },
        )
        valueLabel?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = textColor.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}

/**
 * Quick single-knob access to the low and high ends without opening a band's full editor -
 * mapped onto the strip's lowest and highest bands rather than a separate shelf filter, so
 * there's exactly one thing controlling any given frequency.
 */
@Composable
internal fun ToneControlsRow(
    bassGainDb: Float,
    trebleGainDb: Float,
    balance: Float,
    enabled: Boolean,
    color: Color,
    useGradient: Boolean,
    onBassChange: (Float) -> Unit,
    onTrebleChange: (Float) -> Unit,
    onBalanceChange: (Float) -> Unit,
) {
    val gainRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB
    val balanceRange = -1f..1f
    val bassColor = if (useGradient) valueGradientColor(bassGainDb, gainRange) else color
    val trebleColor = if (useGradient) valueGradientColor(trebleGainDb, gainRange) else color
    val balanceColor = if (useGradient) valueGradientColor(balance, balanceRange) else color

    // Bass, balance and treble together, spread evenly across the full width rather than each
    // getting its own row - three related "tone" controls read as one unit that way, and it's
    // what leaves the balance section below unnecessary.
    //
    // Always dials, regardless of the panel-wide "use dials instead of sliders" setting - the
    // same exemption the 12-band strip below takes in the other direction (always sliders,
    // never dials, because a row of tiny dials is fiddly). Three PowerampSliders read as more
    // band-gain controls and got lost among the 12 actual bands right below them; a knob is what
    // marks these as the different, amp-style tone controls they are.
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        RotaryDial(
            value = bassGainDb,
            onValueChange = onBassChange,
            valueRange = gainRange,
            enabled = enabled,
            dialSize = 84.dp,
            color = bassColor,
            textColor = color,
            centeredAt = 0f,
            label = "Bass",
            valueLabel = "${formatDb(bassGainDb)} dB",
        )
        RotaryDial(
            value = balance,
            onValueChange = onBalanceChange,
            valueRange = balanceRange,
            enabled = enabled,
            dialSize = 84.dp,
            color = balanceColor,
            textColor = color,
            centeredAt = 0f,
            label = "Balance",
            valueLabel = balanceReadout(balance),
        )
        RotaryDial(
            value = trebleGainDb,
            onValueChange = onTrebleChange,
            valueRange = gainRange,
            enabled = enabled,
            dialSize = 84.dp,
            color = trebleColor,
            textColor = color,
            centeredAt = 0f,
            label = "Treble",
            valueLabel = "${formatDb(trebleGainDb)} dB",
        )
    }
}

/**
 * A simple feed-forward dynamics compressor sitting after the band cascade - turns down whatever
 * is already louder than the threshold, then makes the result back up again.
 */
@Composable
internal fun CompressorSection(
    compressor: EqualizerSettings.CompressorSettings,
    onChange: (EqualizerSettings.CompressorSettings) -> Unit,
    color: Color,
    colorVariant: Color,
    useDials: Boolean,
    useGradient: Boolean,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.eq_compressor),
                style = PanelTitleStyle,
                color = color,
            )
            Switch(
                checked = compressor.enabled,
                onCheckedChange = { onChange(compressor.copy(enabled = it)) },
            )
        }

        if (compressor.enabled) {
            Spacer(Modifier.height(4.dp))
            val thresholdRange = EqualizerSettings.MIN_THRESHOLD_DB..EqualizerSettings.MAX_THRESHOLD_DB
            val ratioRange = EqualizerSettings.MIN_RATIO..EqualizerSettings.MAX_RATIO
            val attackRange = EqualizerSettings.MIN_ATTACK_MS..EqualizerSettings.MAX_ATTACK_MS
            val releaseRange = EqualizerSettings.MIN_RELEASE_MS..EqualizerSettings.MAX_RELEASE_MS
            val makeupRange = EqualizerSettings.MIN_MAKEUP_DB..EqualizerSettings.MAX_MAKEUP_DB

            val thresholdColor = if (useGradient) valueGradientColor(compressor.thresholdDb, thresholdRange) else color
            val ratioColor = if (useGradient) valueGradientColor(compressor.ratio, ratioRange) else color
            val attackColor = if (useGradient) valueGradientColor(compressor.attackMs, attackRange) else color
            val releaseColor = if (useGradient) valueGradientColor(compressor.releaseMs, releaseRange) else color
            val makeupColor = if (useGradient) valueGradientColor(compressor.makeupGainDb, makeupRange) else color

            if (useDials) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryDial(
                        value = compressor.attackMs,
                        onValueChange = { onChange(compressor.copy(attackMs = it)) },
                        valueRange = attackRange,
                        color = attackColor,
                        textColor = color,
                        label = "Attack",
                        valueLabel = "${compressor.attackMs.roundToInt()} ms",
                    )
                    RotaryDial(
                        value = compressor.releaseMs,
                        onValueChange = { onChange(compressor.copy(releaseMs = it)) },
                        valueRange = releaseRange,
                        color = releaseColor,
                        textColor = color,
                        label = "Release",
                        valueLabel = "${compressor.releaseMs.roundToInt()} ms",
                    )
                    RotaryDial(
                        value = compressor.ratio,
                        onValueChange = { onChange(compressor.copy(ratio = it)) },
                        valueRange = ratioRange,
                        color = ratioColor,
                        textColor = color,
                        label = "Ratio",
                        valueLabel = "${String.format(java.util.Locale.US, "%.1f", compressor.ratio)}:1",
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryDial(
                        value = compressor.thresholdDb,
                        onValueChange = { onChange(compressor.copy(thresholdDb = quantizeTenth(it))) },
                        valueRange = thresholdRange,
                        color = thresholdColor,
                        textColor = color,
                        label = "Threshold",
                        valueLabel = "${formatDb(compressor.thresholdDb)} dB",
                    )
                    RotaryDial(
                        value = compressor.makeupGainDb,
                        onValueChange = { onChange(compressor.copy(makeupGainDb = quantizeTenth(it))) },
                        valueRange = makeupRange,
                        color = makeupColor,
                        textColor = color,
                        label = "Makeup gain",
                        valueLabel = "${formatDb(compressor.makeupGainDb)} dB",
                    )
                }
            } else {
                LabeledSlider(
                    label = "Threshold: ${formatDb(compressor.thresholdDb)} dB",
                    value = compressor.thresholdDb,
                    onValueChange = { onChange(compressor.copy(thresholdDb = quantizeTenth(it))) },
                    valueRange = thresholdRange,
                    textColor = color,
                )
                LabeledSlider(
                    label = "Ratio: ${String.format(java.util.Locale.US, "%.1f", compressor.ratio)}:1",
                    value = compressor.ratio,
                    onValueChange = { onChange(compressor.copy(ratio = it)) },
                    valueRange = ratioRange,
                    textColor = color,
                )
                LabeledSlider(
                    label = "Attack: ${compressor.attackMs.roundToInt()} ms",
                    value = compressor.attackMs,
                    onValueChange = { onChange(compressor.copy(attackMs = it)) },
                    valueRange = attackRange,
                    textColor = color,
                )
                LabeledSlider(
                    label = "Release: ${compressor.releaseMs.roundToInt()} ms",
                    value = compressor.releaseMs,
                    onValueChange = { onChange(compressor.copy(releaseMs = it)) },
                    valueRange = releaseRange,
                    textColor = color,
                )
                LabeledSlider(
                    label = "Makeup gain: ${formatDb(compressor.makeupGainDb)} dB",
                    value = compressor.makeupGainDb,
                    onValueChange = { onChange(compressor.copy(makeupGainDb = quantizeTenth(it))) },
                    valueRange = makeupRange,
                    textColor = color,
                )
            }
        }
    }
}

/**
 * The handle this panel is opened from also lives at its top while it's open, dragged the other
 * way (up) to close it - the same affordance, reversed, rather than a separate close button doing
 * a different gesture for the same idea. A close icon sits beside it for anyone who'd rather tap.
 */
@Composable
private fun EqualizerPanelHandle(onDismiss: () -> Unit, handleColor: Color) {
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }
    val closeThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }

    // The whole top of the panel is the grab zone, not a slim strip - a small band was easy to
    // miss and drag past, landing on content below that doesn't handle drags itself and so let
    // the gesture fall through to the player underneath.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EqualizerHandleHeight)
            .clickable(onClick = onDismiss)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAccumulatorPx = 0f },
                    onDragCancel = { dragAccumulatorPx = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulatorPx += dragAmount
                        if (dragAccumulatorPx < -closeThresholdPx) {
                            onDismiss()
                            dragAccumulatorPx = 0f
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(64.dp)
                .height(8.dp)
                .background(
                    handleColor.copy(alpha = 0.6f),
                    RoundedCornerShape(4.dp)
                )
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = handleColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BandColumn(
    band: EqualizerSettings.EqBand,
    enabled: Boolean,
    selected: Boolean,
    color: Color,
    useGradient: Boolean,
    onGainChange: (Float) -> Unit,
    onTapLabel: () -> Unit,
) {
    val gainRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB

    // The bands were the one control in the panel that ignored colour-by-value: bass, treble,
    // balance and every compressor dial took their colour from it while these twelve stayed a
    // fixed green, which made the setting look broken rather than deliberate - they are the
    // controls most obviously "a value in a range", so they are where it is expected to show.
    val bandColor = if (useGradient) valueGradientColor(band.gainDb, gainRange) else color

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(BandColumnWidth)
    ) {
        // Twelve of these side by side, so each one stays a plain slider regardless of the
        // panel-wide dial/slider setting - a strip of tiny 48dp dials read as fiddly rather than
        // Poweramp-like, and the reference layout doesn't use them here either. The label stays the
        // plain contrast colour like every other slider's; the track and thumb follow the value.
        Text(
            text = formatDb(band.gainDb),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
        VerticalSlider(
            value = band.gainDb,
            onValueChange = onGainChange,
            valueRange = gainRange,
            enabled = enabled && band.enabled,
            track = { sliderState ->
                PowerampTrack(
                    sliderState = sliderState,
                    activeColor = bandColor,
                    inactiveColor = sliderInactiveColor(),
                    trackThickness = 8.dp,
                    // A band's middle is 0dB, not "none" - see PowerampTrack. Four detents puts a
                    // mark every 7.5dB across the range, which is enough to judge a position by
                    // and few enough not to read as a scale.
                    centerOrigin = true,
                    detents = 4,
                )
            },
            thumb = { PowerampThumb(color = bandColor) },
            modifier = Modifier
                .weight(1f)
                .width(36.dp),
        )
        // Selected reads as "this is the band the editor below belongs to" - a box around the
        // frequency label makes that unambiguous at a glance; the older bold-and-recolor treatment
        // was easy to miss next to eleven other labels the same size. A fixed theme primary here
        // meant this one box ignored the contrast setting everything else in the panel follows -
        // [color] already resolves to the plain theme color when that setting is off, so using it
        // instead of a hardcoded primary makes the highlight track the same setting as everything
        // else, not a separate exception.
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .then(
                    if (selected) {
                        Modifier
                            .border(1.dp, color, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    }
                )
                .clickable(onClick = onTapLabel)
        ) {
            Text(
                text = formatFrequency(band.freqHz),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Basic transport so the panel doesn't have to be closed just to skip a track or pause - it fills
 * the screen the way the queue does, so without this there'd be no way to touch playback at all
 * while it's open. Deliberately the exact same five controls (shuffle, previous, play/pause,
 * next, repeat) as the queue's own transport row, not a smaller ad-hoc set.
 */
@Composable
private fun MiniPlaybackControls(color: Color?) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    // The Material accent color (not a muted onSecondaryContainer neutral) so this row reads as
    // an actively-colored control surface even when "use cover contrast color" is off.
    val iconColor = color ?: MaterialTheme.colorScheme.primary

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        ResizableIconButton(
            icon = if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off,
            color = iconColor,
            modifier = Modifier.size(TransportSize.Secondary),
            onClick = { playerConnection.triggerShuffle() },
        )
        ResizableIconButton(
            icon = Icons.Rounded.SkipPrevious,
            color = iconColor,
            modifier = Modifier.size(TransportSize.Skip),
            onClick = { playerConnection.player.seekToPrevious() },
        )
        ResizableIconButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            color = iconColor,
            modifier = Modifier.size(TransportSize.Primary),
            onClick = { playerConnection.player.togglePlayPause() },
        )
        ResizableIconButton(
            icon = Icons.Rounded.SkipNext,
            color = iconColor,
            modifier = Modifier.size(TransportSize.Skip),
            onClick = { playerConnection.player.seekToNext() },
        )
        ResizableIconButton(
            icon = when (repeatMode) {
                REPEAT_MODE_OFF -> R.drawable.repeat_off
                REPEAT_MODE_ALL -> R.drawable.repeat_on
                REPEAT_MODE_ONE -> R.drawable.repeat_one
                else -> R.drawable.repeat_off
            },
            color = iconColor,
            modifier = Modifier.size(TransportSize.Secondary),
            onClick = { playerConnection.player.toggleRepeatMode() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BandEditor(
    band: EqualizerSettings.EqBand,
    onChange: (EqualizerSettings.EqBand) -> Unit,
    color: Color,
    colorVariant: Color,
    useDials: Boolean,
    useGradient: Boolean,
) {
    val freqRange = log10(EqualizerSettings.MIN_FREQ_HZ)..log10(EqualizerSettings.MAX_FREQ_HZ)
    val gainRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB
    val qRange = EqualizerSettings.MIN_Q..EqualizerSettings.MAX_Q
    val freqColor = if (useGradient) valueGradientColor(log10(band.freqHz), freqRange) else color
    val gainColor = if (useGradient) valueGradientColor(band.gainDb, gainRange) else color

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${formatFrequency(band.freqHz)} band",
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
            Switch(
                checked = band.enabled,
                onCheckedChange = { onChange(band.copy(enabled = it)) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // Frequency and gain here (the selected band's precise editor), while the strip above
        // keeps its own quick, all-bands-at-once overview - one gesture for "tweak this band
        // exactly", another for "eyeball the whole curve". Dials or sliders per the panel-wide
        // setting, same as everywhere else.
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // A frequency control has to be logarithmic, not linear - a linear 16Hz-20kHz range
            // would spend 99% of its travel above 2kHz and leave the entire bass end crammed into
            // a couple of degrees/pixels. Drive it in log-space, convert back to Hz for storage.
            if (useDials) {
                RotaryDial(
                    value = log10(band.freqHz),
                    onValueChange = { onChange(band.copy(freqHz = 10f.pow(it))) },
                    valueRange = freqRange,
                    enabled = band.enabled,
                    color = freqColor,
                    textColor = color,
                    label = "Frequency",
                    valueLabel = formatFrequency(band.freqHz),
                )
                RotaryDial(
                    value = band.gainDb,
                    onValueChange = { onChange(band.copy(gainDb = quantizeTenth(it))) },
                    valueRange = gainRange,
                    enabled = band.enabled,
                    color = gainColor,
                    textColor = color,
                    centeredAt = 0f,
                    label = "Gain",
                    valueLabel = "${formatDb(band.gainDb)} dB",
                )
            } else {
                PowerampSlider(
                    value = log10(band.freqHz),
                    onValueChange = { onChange(band.copy(freqHz = 10f.pow(it))) },
                    valueRange = freqRange,
                    enabled = band.enabled,
                    textColor = color,
                    label = "Frequency",
                    valueLabel = formatFrequency(band.freqHz),
                )
                PowerampSlider(
                    value = band.gainDb,
                    onValueChange = { onChange(band.copy(gainDb = quantizeTenth(it))) },
                    valueRange = gainRange,
                    enabled = band.enabled,
                    textColor = color,
                    label = "Gain",
                    valueLabel = "${formatDb(band.gainDb)} dB",
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LabeledSlider(
            label = "Q: ${String.format(java.util.Locale.US, "%.2f", band.q)}",
            value = band.q,
            onValueChange = { onChange(band.copy(q = it)) },
            valueRange = qRange,
            textColor = color,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Filter type",
            style = MaterialTheme.typography.labelMedium,
            color = colorVariant,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EqualizerSettings.FilterType.entries.forEachIndexed { i, type ->
                SegmentedButton(
                    selected = band.type == type,
                    onClick = { onChange(band.copy(type = type)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = i,
                        count = EqualizerSettings.FilterType.entries.size
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = color.copy(alpha = 0.18f),
                        activeContentColor = color,
                        activeBorderColor = color,
                        // Only the active state was set, so the other four types' labels always
                        // used Material3's default onSurface regardless of the contrast setting.
                        inactiveContentColor = colorVariant,
                        inactiveBorderColor = colorVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Text(
                        text = filterTypeLabel(type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .border(1.dp, textColor.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 10.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = textColor)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            track = { sliderState ->
                PowerampTrack(sliderState = sliderState, activeColor = textColor, inactiveColor = sliderInactiveColor())
            },
            thumb = { PowerampThumb(color = textColor) },
        )
    }
}

private fun filterTypeLabel(type: EqualizerSettings.FilterType) = when (type) {
    EqualizerSettings.FilterType.PEAKING -> "Peak"
    EqualizerSettings.FilterType.LOW_SHELF -> "Low shelf"
    EqualizerSettings.FilterType.HIGH_SHELF -> "High shelf"
    EqualizerSettings.FilterType.LOW_PASS -> "Low pass"
    EqualizerSettings.FilterType.HIGH_PASS -> "High pass"
}

private fun formatFrequency(freqHz: Float): String {
    if (freqHz < 1000f) return freqHz.roundToInt().toString()
    val kHz = freqHz / 1000f
    val rounded = kHz.roundToInt().toFloat()
    return if (kHz == rounded) "${rounded.roundToInt()}k" else "${String.format(java.util.Locale.US, "%.1f", kHz)}k"
}
