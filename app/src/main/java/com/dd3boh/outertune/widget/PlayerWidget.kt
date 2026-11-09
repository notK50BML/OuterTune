/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService

/**
 * The home-screen player: cover, title, artist, and previous / play-pause / next.
 *
 * A classic RemoteViews widget rather than Glance, so it adds no dependency. It holds no state of
 * its own and never polls - [MusicService][com.dd3boh.outertune.playback.MusicService] pushes to it
 * through [PlayerWidget.update] whenever the song or the play state changes, and a widget placed
 * while nothing is playing shows the last thing pushed, or "Nothing playing".
 *
 * The buttons are broadcasts back to this provider, which drives the player through a short-lived
 * [MediaController] - the route Media3 intends for widgets. Media3's own MediaButtonReceiver was
 * the obvious alternative and does not work: on Android 8 and later it ignores every key except
 * "play", so skip and pause would have done nothing. A controller also binds the service, which
 * starts it if it is not running, so play from the widget resumes the saved queue.
 */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        PlayerWidget.pushLast(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in PlayerWidget.COMMANDS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) {
                pending.finish()
                return@addListener
            }
            when (action) {
                PlayerWidget.ACTION_PREVIOUS -> controller.seekToPrevious()
                PlayerWidget.ACTION_NEXT -> controller.seekToNext()
                PlayerWidget.ACTION_PLAY_PAUSE -> if (controller.playWhenReady) {
                    controller.pause()
                } else {
                    if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }
            }
            // Released a moment later rather than at once, so the command is delivered before the
            // connection it travels on goes away.
            Handler(Looper.getMainLooper()).postDelayed({
                controller.release()
                pending.finish()
            }, CONTROLLER_LINGER_MS)
        }, mainExecutor)
    }

    private companion object {
        const val CONTROLLER_LINGER_MS = 1_000L
    }
}

object PlayerWidget {

    /** Big enough for a 56dp cell on a dense screen; small enough to stay far under the binder limit. */
    private const val ART_PX = 256

    @Volatile
    private var lastTitle: String? = null

    @Volatile
    private var lastArtist: String? = null

    @Volatile
    private var lastPlaying = false

    @Volatile
    private var lastArt: Bitmap? = null

    @Volatile
    private var lastArtSongId: String? = null

    /**
     * Shows [metadata] and [playing] on every placed widget. Loads the cover itself, so call it from
     * a coroutine that a newer song will cancel (the service uses collectLatest).
     */
    suspend fun update(context: Context, metadata: MediaMetadata?, playing: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

        lastTitle = metadata?.title
        lastArtist = metadata?.artists?.joinToString { it.name }
        lastPlaying = playing
        if (metadata?.id != lastArtSongId) {
            lastArt = null
            lastArtSongId = metadata?.id
        }
        // Nothing placed: remember the state for when one is, and skip the cover load.
        if (ids.isEmpty()) return

        // Text and buttons straight away; the cover follows once it has loaded.
        pushLast(context, manager, ids)
        if (metadata != null && lastArt == null) {
            val art = loadArt(context, metadata) ?: return
            if (lastArtSongId != metadata.id) return
            lastArt = art
            pushLast(context, manager, ids)
        }
    }

    internal fun pushLast(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
            setTextViewText(R.id.widget_title, lastTitle ?: context.getString(R.string.widget_nothing_playing))
            setTextViewText(R.id.widget_artist, lastArtist.orEmpty())
            setImageViewResource(R.id.widget_play_pause, if (lastPlaying) R.drawable.pause else R.drawable.play)
            val art = lastArt
            if (art != null) setImageViewBitmap(R.id.widget_artwork, art)
            else setImageViewResource(R.id.widget_artwork, R.drawable.placeholder_icon)

            setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_artwork, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_previous, commandIntent(context, ACTION_PREVIOUS, 1))
            setOnClickPendingIntent(R.id.widget_play_pause, commandIntent(context, ACTION_PLAY_PAUSE, 2))
            setOnClickPendingIntent(R.id.widget_next, commandIntent(context, ACTION_NEXT, 3))
        }
        manager.updateAppWidget(ids, views)
    }

    private suspend fun loadArt(context: Context, metadata: MediaMetadata): Bitmap? {
        val model = metadata.getThumbnailModel(ART_PX, ART_PX) ?: return null
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .size(ART_PX, ART_PX)
                // A hardware bitmap cannot be parcelled into RemoteViews.
                .allowHardware(false)
                .build()
        )
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun commandIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setClass(context, PlayerWidgetProvider::class.java)
        // A distinct request code per button, so the three are three intents rather than the last
        // one created standing in for all of them.
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    internal const val ACTION_PREVIOUS = "com.dd3boh.outertune.widget.PREVIOUS"
    internal const val ACTION_PLAY_PAUSE = "com.dd3boh.outertune.widget.PLAY_PAUSE"
    internal const val ACTION_NEXT = "com.dd3boh.outertune.widget.NEXT"
    internal val COMMANDS = setOf(ACTION_PREVIOUS, ACTION_PLAY_PAUSE, ACTION_NEXT)
}
