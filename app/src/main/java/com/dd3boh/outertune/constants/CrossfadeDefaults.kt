package com.dd3boh.outertune.constants

/**
 * Default values and bounds for crossfade.
 *
 * There's only one player, so this isn't a true dual-track overlap - the outgoing track's volume
 * fades out over the last [DURATION_SECONDS] of its own playback, and the incoming track's volume
 * fades back in over its own first [DURATION_SECONDS], both against the same envelope. A track
 * shorter than twice that duration never reaches full volume; see [com.dd3boh.outertune.playback.Crossfader].
 */
object CrossfadeDefaults {
    const val ENABLED = false

    /** Fade length in seconds - how long before a track ends its volume starts fading out. */
    const val DURATION_SECONDS = 8
    val DURATION_RANGE = 1..12
}
