/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * What is currently playing, as far as [DiscordPresence] needs to know.
 *
 * A plain snapshot rather than reading [DesktopPlayer] and [PlayerQueue]'s own state flows
 * directly, so this file does not need to know either shape - `App()` already reads both and is
 * the one place that combines them.
 */
data class DiscordNowPlaying(
    val song: SongItem?,
    val paused: Boolean,
    val positionMs: Long,
)

/**
 * Publishes what is playing to Discord, over the same gateway connection [KizzyRPC] opens on
 * Android - the underlying module is pure `kotlin("jvm")` with no Android dependency, so this is
 * the same client rather than a port of it.
 *
 * Ported from `utils/DiscordRPC.kt`, adapted from composition (a `DiscordRPC` that *is* a
 * `KizzyRPC`) to holding one, since there is no MediaSession-shaped service here to own it - and
 * from a `Song`/`SongEntity` pair to the [SongItem] the desktop queue already deals in.
 */
class DiscordPresence(private val scope: CoroutineScope) {

    private var rpc: KizzyRPC? = null

    /**
     * Single writer, same reason as `MusicService`'s `discordUpdateRequests`: a paused/played tap
     * landing right after a track change must not be free to apply out of order and leave the card
     * describing the wrong song. Debounced so a burst of skips only ever sends the one that stuck.
     */
    private val updates = MutableStateFlow<DiscordNowPlaying?>(null)

    fun start() {
        scope.launch {
            updates.debounce(300).collectLatest { now ->
                val client = rpc ?: return@collectLatest
                runCatching {
                    if (now == null || now.song == null || now.paused) {
                        client.stopActivity()
                    } else {
                        sendPresence(client, now.song, now.positionMs)
                    }
                }.onFailure {
                    // Superseded work unwinding through here would look like it completed - let it
                    // through rather than treating it as a presence failure.
                    if (it is CancellationException) throw it
                }
            }
        }
    }

    /**
     * Rebuilds the client whenever the stored token or the enable switch changes, and re-sends the
     * current state so the card is not blank until the next track. Resending through a dead socket
     * object does not reconnect it, which is why this closes and replaces rather than reusing one.
     */
    fun updateAuth(token: String, enabled: Boolean, now: DiscordNowPlaying?) {
        if (rpc?.isRpcRunning() == true) rpc?.closeRPC()
        rpc = if (token.isNotEmpty() && enabled) KizzyRPC(token) else null
        updates.value = now
    }

    fun requestUpdate(now: DiscordNowPlaying) {
        updates.value = now
    }

    fun shutdown() {
        if (rpc?.isRpcRunning() == true) rpc?.closeRPC()
        rpc = null
    }

    private suspend fun sendPresence(client: KizzyRPC, song: SongItem, positionMs: Long) {
        val currentTime = System.currentTimeMillis()
        val calculatedStartTime = currentTime - positionMs

        // SongItem.duration is seconds and can be null for a track resolved without full metadata.
        // Left alone that would compute an end before the start, which Discord draws as a bar stuck
        // at zero - sending no end at all instead shows elapsed time counting up.
        val durationMs = song.duration?.takeIf { it > 0 }?.times(1000L)
        val calculatedEndTime = durationMs?.let { currentTime + (it - positionMs) }

        // Discord makes details and state clickable when a matching *_url is supplied. The artist
        // credit has no channel id for a result that never resolved one, and stays plain text then.
        val artistUrl = song.artists.firstOrNull()?.id?.let { "https://music.youtube.com/channel/$it" }

        // Every string goes through RpcText first - see that file for why: Discord rejects the
        // *whole* presence when one field is out of range, silently.
        client.setActivity(
            name = "OuterTune",
            details = RpcText.fit(song.title, fallback = "Unknown"),
            detailsUrl = "https://music.youtube.com/watch?v=${song.id}",
            state = RpcText.fit(
                song.artists.joinToString { it.name },
                fallback = "Unknown",
            ),
            stateUrl = artistUrl,
            largeImage = RpcImage.ExternalImage(song.thumbnail),
            smallImage = null,
            largeText = RpcText.fit(song.album?.name),
            smallText = RpcText.fit(song.artists.firstOrNull()?.name),
            buttons = listOf(
                RpcText.fitButton("Listen on YouTube Music") to
                        "https://music.youtube.com/watch?v=${song.id}",
                RpcText.fitButton("Visit OuterTune") to "https://github.com/notK50BML/OuterTune",
            ),
            type = KizzyRPC.Type.LISTENING,
            statusDisplayType = KizzyRPC.StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = calculatedEndTime,
            applicationId = APPLICATION_ID,
        )
    }

    companion object {
        // The same Discord application the Android app presents as, so both platforms show up
        // under one name/icon in Discord's client rather than two different cards.
        private const val APPLICATION_ID = "1411019391843172514"
    }
}
