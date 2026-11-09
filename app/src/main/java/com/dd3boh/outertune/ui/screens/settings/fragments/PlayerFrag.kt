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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Memory
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
import androidx.compose.material.icons.rounded.NoCell
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.runtime.rememberCoroutineScope
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.ShowQueuesBesideCurrentKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioUpscaling
import com.dd3boh.outertune.constants.AudioUpscalingKey
import com.dd3boh.outertune.constants.CrossfadeDefaults
import com.dd3boh.outertune.constants.CrossfadeDurationKey
import com.dd3boh.outertune.constants.CrossfadeKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.DEFAULT_SWIPE_TO_SKIP
import com.dd3boh.outertune.constants.IgnoreAudioFocusKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.LiquidAudioReactiveKey
import com.dd3boh.outertune.constants.LiquidChromaticShockKey
import com.dd3boh.outertune.constants.LiquidColorScheme
import com.dd3boh.outertune.constants.LiquidColorSchemeKey
import com.dd3boh.outertune.constants.LiquidFerrofluidGpuKey
import com.dd3boh.outertune.constants.LiquidFerrofluidQualityKey
import com.dd3boh.outertune.constants.LiquidFerrofluidReactivityKey
import com.dd3boh.outertune.ui.player.FerrofluidQuality
import com.dd3boh.outertune.ui.player.FerrofluidReactivity
import com.dd3boh.outertune.constants.LiquidShapeStyle
import com.dd3boh.outertune.constants.LiquidShapeStyleKey
import com.dd3boh.outertune.constants.LiquidTextContrastKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerAutoTextContrastKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.ShowEqualizerHandleKey
import com.dd3boh.outertune.constants.ShowQueueTitleKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.SleepTimerDefaultMinutesKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.SleepTimerShowOnPlayerKey
import com.dd3boh.outertune.constants.SliderDotKey
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.SliderWeight
import com.dd3boh.outertune.constants.SliderWeightKey
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
fun AudioQualityFrag() {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        key = AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        key = AudioNormalizationKey,
        defaultValue = true
    )
    val (audioUpscaling, onAudioUpscalingChange) = rememberEnumPreference(
        key = AudioUpscalingKey,
        defaultValue = AudioUpscaling.OFF
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
    EnumListPreference(
        title = { Text(stringResource(R.string.audio_upscaling)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = audioUpscaling,
        onValueSelected = onAudioUpscalingChange,
        valueText = {
            when (it) {
                AudioUpscaling.OFF -> stringResource(R.string.audio_upscaling_off)
                AudioUpscaling.SUBTLE -> stringResource(R.string.audio_upscaling_subtle)
                AudioUpscaling.BALANCED -> stringResource(R.string.audio_upscaling_balanced)
                AudioUpscaling.STRONG -> stringResource(R.string.audio_upscaling_strong)
            }
        }
    )
}

/**
 * Between and around songs: crossfading, skipping silence and songs that will not play, and how
 * much of a song makes a play. Follows the audio quality under Playback, as on the desktop.
 */
@Composable
fun PlaybackFrag() {
    val (skipSilence, onSkipSilenceChange) = rememberPreference(key = SkipSilenceKey, defaultValue = false)
    val (minPlaybackDur, onMinPlaybackDurChange) = rememberPreference(minPlaybackDurKey, defaultValue = 30)
    val (skipOnErrorKey, onSkipOnErrorChange) = rememberPreference(key = SkipOnErrorKey, defaultValue = false)
    val (crossfade, onCrossfadeChange) = rememberPreference(key = CrossfadeKey, defaultValue = CrossfadeDefaults.ENABLED)
    val (crossfadeDuration, onCrossfadeDurationChange) = rememberPreference(
        CrossfadeDurationKey,
        defaultValue = CrossfadeDefaults.DURATION_SECONDS
    )

    var showMinPlaybackDur by remember {
        mutableStateOf(false)
    }
    var showCrossfadeDuration by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.crossfade)) },
        description = stringResource(R.string.crossfade_desc),
        icon = { Icon(Icons.Rounded.Timelapse, null) },
        checked = crossfade,
        onCheckedChange = onCrossfadeChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.crossfade_duration)) },
        description = stringResource(R.string.crossfade_duration_desc),
        icon = { Icon(Icons.Rounded.Timelapse, null) },
        isEnabled = crossfade,
        onClick = { showCrossfadeDuration = true }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.skip_silence)) },
        icon = { Icon(painterResource(R.drawable.skip_next), null) },
        checked = skipSilence,
        onCheckedChange = onSkipSilenceChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
        description = stringResource(R.string.auto_skip_next_on_error_desc),
        icon = { Icon(Icons.Rounded.SkipNext, null) },
        checked = skipOnErrorKey,
        onCheckedChange = onSkipOnErrorChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.min_playback_duration)) },
        description = stringResource(R.string.min_playback_duration_summary, minPlaybackDur),
        icon = { Icon(Icons.Rounded.Sync, null) },
        onClick = { showMinPlaybackDur = true }
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
    if (showCrossfadeDuration) {
        CounterDialog(
            title = stringResource(R.string.crossfade_duration),
            description = stringResource(R.string.crossfade_duration_desc),
            initialValue = crossfadeDuration,
            upperBound = CrossfadeDefaults.DURATION_RANGE.last,
            lowerBound = CrossfadeDefaults.DURATION_RANGE.first,
            unitDisplay = " " + stringResource(R.string.sleep_timer_second_unit),
            onDismiss = { showCrossfadeDuration = false },
            onConfirm = {
                showCrossfadeDuration = false
                onCrossfadeDurationChange(it)
            },
            onCancel = {
                showCrossfadeDuration = false
            }
        )
    }
}

/**
 * The player's buttons and gestures. How the player looks - its background, its layout and the
 * seek bar - is under Appearance, as on the desktop.
 */
@Composable
fun PlayerControlsFrag() {
    val context = LocalContext.current
    val (seekIncrement, onSeekIncrementChange) = rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )
    val (swipeToSkip, onSwipeToSkipChange) = rememberPreference(SwipeToSkipKey, defaultValue = DEFAULT_SWIPE_TO_SKIP)
    val (showEqualizerHandle, onShowEqualizerHandleChange) = rememberPreference(
        ShowEqualizerHandleKey,
        defaultValue = true
    )

    // Replaces the old "show equaliser button" and "show sleep timer on player" switches: every
    // action can be a button now, in any order. Their stored values seed it until it is first saved.
    PlayerButtonsArrangement()
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
        title = { Text(stringResource(R.string.swipe_to_skip_title)) },
        description = stringResource(R.string.swipe_to_skip_description),
        icon = { Icon(Icons.Rounded.Swipe, null) },
        checked = swipeToSkip,
        onCheckedChange = onSwipeToSkipChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_equalizer_handle_title)) },
        description = stringResource(R.string.show_equalizer_handle_description),
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        checked = showEqualizerHandle,
        onCheckedChange = onShowEqualizerHandleChange
    )
}

/**
 * The seek bar under the cover: its style, thickness and dot. Under Appearance with the rest of the
 * player's look, where the desktop has it too.
 */
@Composable
fun SeekBarFrag() {
    val (sliderStyle, onSliderStyleChange) = rememberEnumPreference(SliderStyleKey, defaultValue = DEFAULT_SLIDER_STYLE)
    val (sliderWeight, onSliderWeightChange) = rememberEnumPreference(SliderWeightKey, defaultValue = SliderWeight.THINNER)
    val (sliderDot, onSliderDotChange) = rememberPreference(SliderDotKey, defaultValue = false)

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
                SliderStyle.WAVES -> stringResource(R.string.slider_style_waves)
                SliderStyle.WAVE_LINES -> stringResource(R.string.slider_style_wave_lines)
            }
        }
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.slider_weight_title)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = sliderWeight,
        onValueSelected = onSliderWeightChange,
        valueText = {
            when (it) {
                SliderWeight.DEFAULT -> stringResource(R.string.slider_style_default)
                SliderWeight.THINNER -> stringResource(R.string.slider_weight_thinner)
            }
        }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.slider_dot_title)) },
        description = stringResource(R.string.slider_dot_description),
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        checked = sliderDot,
        onCheckedChange = onSliderDotChange
    )
}

/**
 * Everything about the queue in one place: what is kept, what is loaded, and the saved queues. The
 * last two came from Experimental, where nobody would have looked for them.
 */
@Composable
fun QueueFrag() {
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(key = PersistentQueueKey, defaultValue = true)
    val (showQueueTitle, onShowQueueTitleChange) = rememberPreference(ShowQueueTitleKey, defaultValue = true)
    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(AutoLoadMoreKey, defaultValue = true)
    val (maxQueues, onMaxQueuesChange) = rememberPreference(MaxQueuesKey, defaultValue = 19)
    val (showQueuesBesideCurrent, onShowQueuesBesideCurrentChange) = rememberPreference(
        ShowQueuesBesideCurrentKey,
        defaultValue = false
    )

    var showMaxQueuesDialog by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.persistent_queue)) },
        description = stringResource(R.string.persistent_queue_desc_ot),
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        checked = persistentQueue,
        onCheckedChange = onPersistentQueueChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_queue_title)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        checked = showQueueTitle,
        onCheckedChange = onShowQueueTitleChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.auto_load_more)) },
        description = stringResource(R.string.auto_load_more_desc),
        icon = { Icon(Icons.Rounded.Autorenew, null) },
        checked = autoLoadMore,
        onCheckedChange = onAutoLoadMoreChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.show_queues_beside_current_title)) },
        description = stringResource(R.string.show_queues_beside_current_description),
        icon = { Icon(Icons.Rounded.Queue, null) },
        checked = showQueuesBesideCurrent,
        onCheckedChange = onShowQueuesBesideCurrentChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.max_queues_title)) },
        description = maxQueues.toString(),
        icon = { Icon(Icons.Rounded.Queue, null) },
        onClick = { showMaxQueuesDialog = true }
    )

    if (showMaxQueuesDialog) {
        CounterDialog(
            title = stringResource(R.string.max_queues_title),
            initialValue = maxQueues,
            upperBound = 30,
            lowerBound = 1,
            onDismiss = { showMaxQueuesDialog = false },
            onConfirm = {
                showMaxQueuesDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    onMaxQueuesChange(it)
                    delay(500)
                    // the queues get reloaded, but not cleared by the database
                    // this will allow the user to (immediately) revert any accidental changes
                    playerConnection?.service?.initQueue()
                }
            },
            onCancel = {
                showMaxQueuesDialog = false
            }
        )
    }
}

/**
 * How the app gets on with the phone: whether the system may close it, whether swiping it away
 * stops the music, and whether it gives way to other apps' sound. Under Advanced on Player and
 * audio - the phone's counterpart of the desktop's close-to-tray, which is under System there.
 */
@Composable
fun BackgroundPlaybackFrag() {
    val (keepAlive, onKeepAliveChange) = rememberPreference(key = KeepAliveKey, defaultValue = false)
    // Matches MusicService.onTaskRemoved's own default (true) - shown as off here while actually
    // on there made the switch lie about the app's real behaviour for anyone who had never
    // touched it.
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = true
    )
    val (ignoreAudioFocus, onIgnoreAudioFocusChange) = rememberPreference(key = IgnoreAudioFocusKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.keep_alive_title)) },
        description = stringResource(R.string.keep_alive_description),
        icon = { Icon(Icons.Rounded.NoCell, null) },
        checked = keepAlive,
        onCheckedChange = {
            if (it) {
                onStopMusicOnTaskClearChange(false)
            }
            onKeepAliveChange(it)
        }
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
private fun AudioQualityFragPreview() {
    AudioQualityFrag()
}

@Preview(showBackground = true)
@Composable
private fun PlayerControlsFragPreview() {
    PlayerControlsFrag()
}

@Preview(showBackground = true)
@Composable
private fun QueueFragPreview() {
    QueueFrag()
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
            title = { Text(stringResource(R.string.liquid_audio_reactive)) },
            description = stringResource(R.string.liquid_audio_reactive_desc),
            icon = { Icon(Icons.Rounded.GraphicEq, null) },
            checked = liquidAudioReactive,
            onCheckedChange = onLiquidAudioReactiveChange,
        )
    }

    val (liquidShapeStyle, onLiquidShapeStyleChange) =
        rememberEnumPreference(LiquidShapeStyleKey, defaultValue = LiquidShapeStyle.PETAL)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        EnumListPreference(
            title = { Text(stringResource(R.string.liquid_shape)) },
            icon = { Icon(Icons.Rounded.Grain, null) },
            selectedValue = liquidShapeStyle,
            onValueSelected = onLiquidShapeStyleChange,
            valueText = {
                when (it) {
                    LiquidShapeStyle.PETAL -> stringResource(R.string.liquid_shape_petal)
                    LiquidShapeStyle.SPHERES -> stringResource(R.string.liquid_shape_spheres)
                    LiquidShapeStyle.FERROFLUID -> stringResource(R.string.liquid_shape_ferrofluid)
                }
            },
            values = LiquidShapeStyle.entries,
        )
    }

    val (liquidColorScheme, onLiquidColorSchemeChange) =
        rememberEnumPreference(LiquidColorSchemeKey, defaultValue = LiquidColorScheme.SURFACE)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        EnumListPreference(
            title = { Text(stringResource(R.string.liquid_colour_scheme)) },
            icon = { Icon(Icons.Rounded.Palette, null) },
            selectedValue = liquidColorScheme,
            onValueSelected = onLiquidColorSchemeChange,
            valueText = {
                when (it) {
                    LiquidColorScheme.SURFACE -> stringResource(R.string.liquid_colour_surface)
                    LiquidColorScheme.BLACK -> stringResource(R.string.liquid_colour_black)
                    LiquidColorScheme.WHITE -> stringResource(R.string.liquid_colour_white)
                }
            },
            values = LiquidColorScheme.entries,
        )
    }

    val (liquidTextContrast, onLiquidTextContrastChange) =
        rememberPreference(LiquidTextContrastKey, defaultValue = true)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        SwitchPreference(
            title = { Text(stringResource(R.string.liquid_text_contrast)) },
            description = stringResource(R.string.liquid_text_contrast_desc),
            icon = { Icon(Icons.Rounded.Contrast, null) },
            checked = liquidTextContrast,
            onCheckedChange = onLiquidTextContrastChange,
        )
    }

    val (liquidChromaticShock, onLiquidChromaticShockChange) =
        rememberPreference(LiquidChromaticShockKey, defaultValue = true)
    AnimatedVisibility(visible = playerBackground == PlayerBackgroundStyle.LIQUID) {
        SwitchPreference(
            title = { Text(stringResource(R.string.liquid_chromatic_shock)) },
            description = stringResource(R.string.liquid_chromatic_shock_desc),
            icon = { Icon(Icons.Rounded.Bolt, null) },
            checked = liquidChromaticShock,
            onCheckedChange = onLiquidChromaticShockChange,
        )
    }

    val (liquidFerrofluidGpu, onLiquidFerrofluidGpuChange) =
        rememberPreference(LiquidFerrofluidGpuKey, defaultValue = false)
    AnimatedVisibility(
        visible = playerBackground == PlayerBackgroundStyle.LIQUID &&
            liquidShapeStyle == LiquidShapeStyle.FERROFLUID
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.liquid_ferrofluid_gpu)) },
            description = stringResource(R.string.liquid_ferrofluid_gpu_desc),
            icon = { Icon(Icons.Rounded.Memory, null) },
            checked = liquidFerrofluidGpu,
            onCheckedChange = onLiquidFerrofluidGpuChange,
        )
    }

    val (liquidFerrofluidReactivity, onLiquidFerrofluidReactivityChange) =
        rememberEnumPreference(LiquidFerrofluidReactivityKey, defaultValue = FerrofluidReactivity.NORMAL)
    val (liquidFerrofluidQuality, onLiquidFerrofluidQualityChange) =
        rememberEnumPreference(LiquidFerrofluidQualityKey, defaultValue = FerrofluidQuality.MEDIUM)
    AnimatedVisibility(
        visible = playerBackground == PlayerBackgroundStyle.LIQUID &&
            liquidShapeStyle == LiquidShapeStyle.FERROFLUID &&
            liquidFerrofluidGpu
    ) {
        EnumListPreference(
            title = { Text(stringResource(R.string.liquid_ferrofluid_quality)) },
            icon = { Icon(Icons.Rounded.Memory, null) },
            selectedValue = liquidFerrofluidQuality,
            onValueSelected = onLiquidFerrofluidQualityChange,
            valueText = {
                when (it) {
                    FerrofluidQuality.LOW -> stringResource(R.string.liquid_ferrofluid_quality_low)
                    FerrofluidQuality.MEDIUM -> stringResource(R.string.liquid_ferrofluid_quality_medium)
                    FerrofluidQuality.HIGH -> stringResource(R.string.liquid_ferrofluid_quality_high)
                    FerrofluidQuality.ULTRA -> stringResource(R.string.liquid_ferrofluid_quality_ultra)
                }
            },
            values = FerrofluidQuality.entries,
        )
    }

    // Its own AnimatedVisibility rather than sharing the one above. AnimatedVisibility lays its
    // content out in a Box, so two preferences inside a single block are drawn on top of each
    // other instead of one under the next.
    AnimatedVisibility(
        visible = playerBackground == PlayerBackgroundStyle.LIQUID &&
            liquidShapeStyle == LiquidShapeStyle.FERROFLUID &&
            liquidFerrofluidGpu
    ) {
        EnumListPreference(
            title = { Text(stringResource(R.string.liquid_ferrofluid_reactivity)) },
            icon = { Icon(Icons.Rounded.GraphicEq, null) },
            selectedValue = liquidFerrofluidReactivity,
            onValueSelected = onLiquidFerrofluidReactivityChange,
            valueText = {
                when (it) {
                    FerrofluidReactivity.SUBTLE -> stringResource(R.string.liquid_ferrofluid_reactivity_subtle)
                    FerrofluidReactivity.NORMAL -> stringResource(R.string.liquid_ferrofluid_reactivity_normal)
                    FerrofluidReactivity.HIGH -> stringResource(R.string.liquid_ferrofluid_reactivity_high)
                    FerrofluidReactivity.EXTREME -> stringResource(R.string.liquid_ferrofluid_reactivity_extreme)
                }
            },
            values = FerrofluidReactivity.entries,
        )
    }
}
