package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A volume envelope that fades the current track out before it ends and fades the next one back
 * in after it starts, so the switch between tracks isn't an abrupt cut.
 *
 * This is one [Player] playing one track at a time, not two tracks mixed together - a real
 * overlapping crossfade needs two decoders running at once, which this app's single-ExoPlayer
 * pipeline doesn't have. What this gives instead is the fade-out/fade-in envelope most single-
 * engine players fall back to, which reads the same way in practice: [fadeFactor] is
 * `min(elapsed, remaining) / durationMs`, clamped to `[0, 1]` - 0 right at the start of a track,
 * ramping to 1 once [durationMs] has elapsed, then back down to 0 over the last [durationMs]
 * before the track ends. A track shorter than `2 * durationMs` never reaches 1; that's the same
 * "crossfade longer than the song" edge case every implementation of this has; nothing to fix.
 *
 * [fadeFactor] is meant to be multiplied into the player's volume alongside every other factor
 * (user volume, loudness normalization, sleep timer fade) - see how [SleepTimer.fadeFactor] is
 * combined in [MusicService].
 */
class Crossfader(
    private val scope: CoroutineScope,
    private val player: Player,
) {
    @Volatile
    var enabled: Boolean = false

    /** Length of both the fade-in and the fade-out, in milliseconds. */
    @Volatile
    var durationMs: Long = 0L

    private val _fadeFactor = MutableStateFlow(1f)
    val fadeFactor: StateFlow<Float> = _fadeFactor.asStateFlow()

    private var job: Job? = null

    /** Starts the continuous fade-envelope watch. Call once; it runs for the service's lifetime. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                if (!enabled || durationMs <= 0L) {
                    _fadeFactor.value = 1f
                    delay(POLL_MS)
                    continue
                }

                val position = player.currentPosition
                val duration = player.duration
                val fadeIn = (position.toFloat() / durationMs).coerceIn(0f, 1f)
                val fadeOut = if (duration == C.TIME_UNSET || duration <= 0L) {
                    1f
                } else {
                    ((duration - position).toFloat() / durationMs).coerceIn(0f, 1f)
                }
                _fadeFactor.value = minOf(fadeIn, fadeOut)
                delay(TICK_MS)
            }
        }
    }

    companion object {
        private const val TICK_MS = 50L
        private const val POLL_MS = 1_000L
    }
}
