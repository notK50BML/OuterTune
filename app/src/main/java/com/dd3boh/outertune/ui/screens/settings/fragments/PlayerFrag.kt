package com.dd3boh.outertune.ui.screens.settings.fragments

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DEFAULT_SHOW_LYRICS_ON_CLICK
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.DEFAULT_SWIPE_TO_SKIP
import com.dd3boh.outertune.constants.IgnoreAudioFocusKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.LiquidAudioReactiveKey
import com.dd3boh.outertune.constants.LiquidColorScheme
import com.dd3boh.outertune.constants.LiquidColorSchemeKey
import com.dd3boh.outertune.constants.LiquidShapeStyle
import com.dd3boh.outertune.constants.LiquidShapeStyleKey
import com.dd3boh.outertune.constants.LiquidTextContrastKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerAutoTextContrastKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.ShowEqualizerButtonKey
import com.dd3boh.outertune.constants.ShowEqualizerHandleKey
import com.dd3boh.outertune.constants.ShowLyricsOnClickKey
import com.dd3boh.outertune.constants.ShowQueueTitleKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.SleepTimerDefaultMinutesKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.SleepTimerShowOnPlayerKey
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.SwipeToSkipKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.CounterDialog
import com.dd3boh.outertune.ui.menu.SleepTimerDefaultTimeDialog
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun PlayerGeneralFrag() {
    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(AutoLoadMoreKey, defaultValue = true)
    val (skipSilence, onSkipSilenceChange) = rememberPreference(key = SkipSilenceKey, defaultValue = false)

    val context = LocalContext.current
    val (seekIncrement, onSeekIncrementChange) = rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.auto_load_more)) },
        description = stringResource(R.string.auto_load_more_desc),
        icon = { Icon(Icons.Rounded.Autorenew, null) },
        checked = autoLoadMore,
        onCheckedChange = onAutoLoadMoreChange
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.seek_increment))},
        icon = { Icon(Icons.Rounded.FastForward, null) },
        selectedValue = seekIncrement,
        onValueSelected = onSeekIncrementChange,
        valueText = {
            seekIncrement -> SeekIncrement.getString(context, seekIncrement)
        }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.skip_silence)) },
        icon = { Icon(painterResource(R.drawable.skip_next), null) },
        checked = skipSilence,
        onCheckedChange = onSkipSilenceChange
    )
}

@Composable
fun PlayerServiceFrag() {

}

@Composable
fun AudioQualityFrag() {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        key = AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        key = AudioNormalizationKey,
        defaultValue = true
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.audio_quality)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = audioQuality,
        onValueSelected = onAudioQualityChange,
        valueText = {
            when (it) {
                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
            }
        }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.audio_normalization)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        checked = audioNormalization,
        onCheckedChange = onAudioNormalizationChange
    )
}

@Composable
fun NowPlayingFrag() {
    val (showQueueTitle, onShowQueueTitleChange) = rememberPreference(ShowQueueTitleKey, defaultValue = true)
    val (sliderStyle, onSliderStyleChange) = rememberEnumPreference(SliderStyleKey, defaultValue = DEFAULT_SLIDER_STYLE)
    val (swipeToSkip, onSwipeToSkipChange) = rememberPreference(SwipeToSkipKey, defaultValue = DEFAULT_SWIPE_TO_SKIP)
    val (showLyricsOnClick, onShowLyricsOnClickChange) = rememberPreference(
        ShowLyricsOnClickKey,
        defaultValue = DEFAULT_SHOW_LYRICS_ON_CLICK
    )
    val (showEqualizerButton, onShowEqualizerButtonChange) = rememberPreference(
        ShowEqualizerButtonKey,
        defaultValue = true
    )
    val (showEqualizerHandle, onShowEqualizerHandleChange) = rememberPreference(
        ShowEqualizerHandleKey,
        defaultValue = true
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.slider_style_title)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = sliderStyle,
        onValueSelected = onSliderStyleChange,
        valueText = {
            when (it) {
                SliderStyle.SQUIGGLY -> stringResource(R.string.slider_style_squiggly)
                SliderStyle.DEFAULT -> stringResource(R.string.slider_style_default)
                SliderStyle.SLIM -> stringResource(R.string.slider_style_slim)
            }
        }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.swipe_to_skip_title)) },
        description = stringResource(R.string.swipe_to_skip_description),
        icon = { Icon(Icons.Rounded.Swipe, null) },
        checked = swipeToSkip,
        onCheckedChange = onSwipeToSkipChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_queue_title)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        checked = showQueueTitle,
        onCheckedChange = onShowQueueTitleChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.tap_artwork_to_show_lyrics_title)) },
        description = stringResource(R.string.tap_artwork_to_show_lyrics_description),
        icon = { Icon(Icons.Rounded.Lyrics, null) },
        checked = showLyricsOnClick,
        onCheckedChange = onShowLyricsOnClickChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_equalizer_button_title)) },
        description = stringResource(R.string.show_equalizer_button_description),
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        checked = showEqualizerButton,
        onCheckedChange = onShowEqualizerButtonChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_equalizer_handle_title)) },
        description = stringResource(R.string.show_equalizer_handle_description),
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        checked = showEqualizerHandle,
        onCheckedChange = onShowEqualizerHandleChange
    )
}

@Composable
fun PlaybackBehaviourFrag() {
    val keepAlive by rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(key = PersistentQueueKey, defaultValue = true)
    val (minPlaybackDur, onMinPlaybackDurChange) = rememberPreference(minPlaybackDurKey, defaultValue = 30)
    val (skipOnErrorKey, onSkipOnErrorChange) = rememberPreference(key = SkipOnErrorKey, defaultValue = false)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = false
    )
    val (ignoreAudioFocus, onIgnoreAudioFocusChange) = rememberPreference(key = IgnoreAudioFocusKey, defaultValue = false)

    var showMinPlaybackDur by remember {
        mutableStateOf(false)
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.min_playback_duration)) },
        description = stringResource(R.string.min_playback_duration_summary, minPlaybackDur),
        icon = { Icon(Icons.Rounded.Sync, null) },
        onClick = { showMinPlaybackDur = true }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
        description = stringResource(R.string.auto_skip_next_on_error_desc),
        icon = { Icon(Icons.Rounded.SkipNext, null) },
        checked = skipOnErrorKey,
        onCheckedChange = onSkipOnErrorChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        isEnabled = !keepAlive,
        checked = stopMusicOnTaskClear,
        onCheckedChange = onStopMusicOnTaskClearChange,
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.ignore_audio_focus)) },
        description = stringResource(R.string.ignore_audio_focus_desc),
        icon = { Icon(Icons.Rounded.Headset, null) },
        checked = ignoreAudioFocus,
        onCheckedChange = onIgnoreAudioFocusChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.persistent_queue)) },
        description = stringResource(R.string.persistent_queue_desc_ot),
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        checked = persistentQueue,
        onCheckedChange = onPersistentQueueChange
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showMinPlaybackDur) {
        CounterDialog(
            title = stringResource(R.string.min_playback_duration),
            description = stringResource(R.string.min_playback_duration_description),
            initialValue = minPlaybackDur,
            upperBound = 100,
            lowerBound = 0,
            unitDisplay = "%",
            onDismiss = { showMinPlaybackDur = false },
            onConfirm = {
                showMinPlaybackDur = false
                onMinPlaybackDurChange(it)
            },
            onCancel = {
                showMinPlaybackDur = false
            }
        )
    }
}

@Composable
fun SleepTimerFrag() {
    val (fade, onFadeChange) = rememberPreference(SleepTimerFadeKey, defaultValue = SleepTimerDefaults.FADE_ENABLED)
    val (fadeDuration, onFadeDurationChange) = rememberPreference(
        SleepTimerFadeDurationKey,
        defaultValue = SleepTimerDefaults.FADE_DURATION_SECONDS
    )
    val (defaultMinutes, onDefaultMinutesChange) = rememberPreference(
        SleepTimerDefaultMinutesKey,
        defaultValue = SleepTimerDefaults.DEFAULT_MINUTES
    )
    val (showOnPlayer, onShowOnPlayerChange) = rememberPreference(SleepTimerShowOnPlayerKey, defaultValue = true)

    var showFadeDurationDialog by remember { mutableStateOf(false) }
    var showDefaultTimeDialog by remember { mutableStateOf(false) }

    SwitchPreference(
        title = { Text(stringResource(R.string.sleep_timer_show_on_player)) },
        description = stringResource(R.string.sleep_timer_show_on_player_desc),
        icon = { Icon(Icons.Rounded.Visibility, null) },
        checked = showOnPlayer,
        onCheckedChange = onShowOnPlayerChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.sleep_timer_fade)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeDown, null) },
        checked = fade,
        onCheckedChange = onFadeChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.sleep_timer_fade_duration)) },
        description = stringResource(R.string.sleep_timer_fade_duration_desc),
        icon = { Icon(Icons.Rounded.Timelapse, null) },
        isEnabled = fade,
        onClick = { showFadeDurationDialog = true }
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.sleep_timer_default_time)) },
        description = pluralStringResource(R.plurals.minute, defaultMinutes, defaultMinutes),
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        onClick = { showDefaultTimeDialog = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showFadeDurationDialog) {
        CounterDialog(
            title = stringResource(R.string.sleep_timer_fade_duration),
            description = stringResource(R.string.sleep_timer_fade_duration_desc),
            initialValue = fadeDuration,
            upperBound = SleepTimerDefaults.FADE_DURATION_RANGE.last,
            lowerBound = SleepTimerDefaults.FADE_DURATION_RANGE.first,
            unitDisplay = " " + stringResource(R.string.sleep_timer_second_unit),
            onDismiss = { showFadeDurationDialog = false },
            onConfirm = {
                showFadeDurationDialog = false
                onFadeDurationChange(it)
            },
            onCancel = { showFadeDurationDialog = false }
        )
    }
    if (showDefaultTimeDialog) {
        SleepTimerDefaultTimeDialog(
            initialMinutes = defaultMinutes,
            onDismiss = { showDefaultTimeDialog = false },
            onConfirm = {
                showDefaultTimeDialog = false
                onDefaultMinutesChange(it)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayerGeneralFragPreview() {
    PlayerGeneralFrag()
}

@Preview(showBackground = true)
@Composable
private fun AudioQualityFragPreview() {
    AudioQualityFrag()
}

@Preview(showBackground = true)
@Composable
private fun NowPlayingFragPreview() {
    NowPlayingFrag()
}

@Preview(showBackground = true)
@Composable
private fun PlaybackBehaviourFragPreview() {
    PlaybackBehaviourFrag()
}

/**
 * The player background lives under Appearance rather than with the playback settings: it is a
 * question about how the app looks, and that is where people go looking for it.
 */
@Composable
fun PlayerBackgroundFrag() {
    val (playerBackground, onPlayerBackgroundChange) = rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )
    val (autoTextContrast, onAutoTextContrastChange) =
        rememberPreference(PlayerAutoTextContrastKey, defaultValue = true)

    // Blur is hidden below Android 12, where Modifier.blur silently does nothing. Frosted glass
    // gets its blur from a tiny upscaled bitmap instead, so it stays available everywhere.
    val availableBackgroundStyles = PlayerBackgroundStyle.entries.filter {
        it != PlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    EnumListPreference(
        title = { Text(stringResource(R.string.player_background_style)) },
        icon = { Icon(Icons.Rounded.BlurOn, null) },
        selectedValue = playerBackground,
        onValueSelected = onPlayerBackgroundChange,
        valueText = {
            when (it) {
                PlayerBackgroundStyle.LIQUID -> stringResource(R.string.player_background_liquid)
                PlayerBackgroundStyle.FOLLOW_THEME -> stringResource(R.string.player_background_default)
                PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.player_background_gradient)
                PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                PlayerBackgroundStyle.FROSTED -> stringResource(R.string.player_background_frosted)
            }
        },
        values = availableBackgroundStyles
    )
    // Only offered for the backgrounds drawn from the artwork; the others have no brightness to
    // read, so the switch would sit there doing nothing.
    AnimatedVisibility(
        visible = playerBackground == PlayerBackgroundStyle.FROSTED ||
                playerBackground == PlayerBackgroundStyle.BLUR
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.player_auto_text_contrast_title)) },
            description = stringResource(R.string.player_auto_text_contrast_description),
            icon = { Icon(Icons.Rounded.Contrast, null) },
            checked = autoTextContrast,
            onCheckedChange = onAutoTextContrastChange,
        )
    }

    val (liquidAudioReactive, onLiquidAudioReactiveChange) =
        rememberPreference(LiquidAudioReactiveKey, defaultValue = true)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        SwitchPreference(
            title = { Text("Audio-reactive") },
            description = "Let the blobs respond to bass, treble and beats instead of drifting on their own",
            icon = { Icon(Icons.Rounded.GraphicEq, null) },
            checked = liquidAudioReactive,
            onCheckedChange = onLiquidAudioReactiveChange,
        )
    }

    val (liquidShapeStyle, onLiquidShapeStyleChange) =
        rememberEnumPreference(LiquidShapeStyleKey, defaultValue = LiquidShapeStyle.PETAL)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        EnumListPreference(
            title = { Text("Liquid shape") },
            icon = { Icon(Icons.Rounded.Grain, null) },
            selectedValue = liquidShapeStyle,
            onValueSelected = onLiquidShapeStyleChange,
            valueText = {
                when (it) {
                    LiquidShapeStyle.PETAL -> "Petal"
                    LiquidShapeStyle.SPHERES -> "Spheres"
                }
            },
            values = LiquidShapeStyle.entries,
        )
    }

    val (liquidColorScheme, onLiquidColorSchemeChange) =
        rememberEnumPreference(LiquidColorSchemeKey, defaultValue = LiquidColorScheme.SURFACE)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        EnumListPreference(
            title = { Text("Liquid colour scheme") },
            icon = { Icon(Icons.Rounded.Palette, null) },
            selectedValue = liquidColorScheme,
            onValueSelected = onLiquidColorSchemeChange,
            valueText = {
                when (it) {
                    LiquidColorScheme.SURFACE -> "Theme surface"
                    LiquidColorScheme.BLACK -> "Black"
                    LiquidColorScheme.WHITE -> "White"
                }
            },
            values = LiquidColorScheme.entries,
        )
    }

    val (liquidTextContrast, onLiquidTextContrastChange) =
        rememberPreference(LiquidTextContrastKey, defaultValue = true)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        SwitchPreference(
            title = { Text("Auto text contrast") },
            description = "Flip player/queue/lyrics text to the opposite of the liquid background's measured brightness",
            icon = { Icon(Icons.Rounded.Contrast, null) },
            checked = liquidTextContrast,
            onCheckedChange = onLiquidTextContrastChange,
        )
    }
}
