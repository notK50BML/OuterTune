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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.SliderWeight
import com.dd3boh.outertune.constants.SliderWeightKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin

/**
 * The home-screen player: cover, title, artist, previous / play-pause / next, and a seek bar.
 *
 * A classic RemoteViews widget rather than Glance, so it adds no dependency. It holds no state of
 * its own and never polls - [MusicService] pushes to it through [PlayerWidget.update] whenever the
 * song or the play state changes, and nudges the seek bar every couple of seconds while playing.
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

    /** The drawn background and seek bar, scaled to fit the widget. Wide, like the widget itself. */
    private const val BG_W = 720
    private const val BG_H = 300
    private const val SEEK_W = 900
    private const val SEEK_H = 42

    /** How round the background's corners are, in background pixels - about the launcher's own. */
    private const val CORNER_PX = 44f

    /** What the player looks like, as far as the widget copies it. */
    private data class Look(val background: PlayerBackgroundStyle, val slider: SliderStyle, val weight: SliderWeight)

    /** The colours one look resolves to - see [colours]. */
    private data class Colours(val background: Int, val text: Int, val subtext: Int, val accent: Int, val track: Int)

    @Volatile private var lastTitle: String? = null
    @Volatile private var lastArtist: String? = null
    @Volatile private var lastPlaying = false
    @Volatile private var lastFraction = 0f
    @Volatile private var lastArt: Bitmap? = null
    @Volatile private var lastArtSongId: String? = null
    @Volatile private var lastLook = Look(DEFAULT_PLAYER_BACKGROUND, DEFAULT_SLIDER_STYLE, SliderWeight.THINNER)

    /** The drawn background, kept until the song, the look or light/dark changes. */
    @Volatile private var lastBackground: Bitmap? = null
    @Volatile private var lastBackgroundKey: String? = null

    private fun ids(context: Context, manager: AppWidgetManager) =
        manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

    /**
     * Shows [metadata], [playing] and how far through the song it is on every placed widget. Loads
     * the cover itself, so call it from a coroutine that a newer song will cancel (the service uses
     * collectLatest).
     */
    suspend fun update(context: Context, metadata: MediaMetadata?, playing: Boolean, fraction: Float) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context, manager)

        lastTitle = metadata?.title
        lastArtist = metadata?.artists?.joinToString { it.name }
        lastPlaying = playing
        lastFraction = fraction
        if (metadata?.id != lastArtSongId) {
            lastArt = null
            lastArtSongId = metadata?.id
        }
        // Nothing placed: remember the state for when one is, and skip the work.
        if (ids.isEmpty()) return

        val prefs = context.dataStore.data.first()
        lastLook = Look(
            background = prefs[PlayerBackgroundStyleKey]
                ?.let { stored -> PlayerBackgroundStyle.entries.find { it.name == stored } } ?: DEFAULT_PLAYER_BACKGROUND,
            slider = prefs[SliderStyleKey]?.let { stored -> SliderStyle.entries.find { it.name == stored } } ?: DEFAULT_SLIDER_STYLE,
            weight = prefs[SliderWeightKey]?.let { stored -> SliderWeight.entries.find { it.name == stored } } ?: SliderWeight.THINNER,
        )

        // Text and buttons straight away; the cover, and the background drawn from it, follow.
        pushLast(context, manager, ids)
        if (metadata != null && lastArt == null) {
            val art = loadArt(context, metadata) ?: return
            if (lastArtSongId != metadata.id) return
            lastArt = art
            pushLast(context, manager, ids)
        }
    }

    /** Moves only the seek bar. Cheap enough to call every couple of seconds while playing. */
    fun updateProgress(context: Context, fraction: Float) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context, manager)
        if (ids.isEmpty()) return
        lastFraction = fraction
        val colours = colours(context, lastLook.background, lastArt != null)
        val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
            setImageViewBitmap(R.id.widget_seekbar, seekBar(context, fraction, lastLook, colours))
        }
        manager.partiallyUpdateAppWidget(ids, views)
    }

    internal fun pushLast(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val art = lastArt
        val look = lastLook
        val colours = colours(context, look.background, art != null)
        val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
            setTextViewText(R.id.widget_title, lastTitle ?: context.getString(R.string.widget_nothing_playing))
            setTextViewText(R.id.widget_artist, lastArtist.orEmpty())
            setTextColor(R.id.widget_title, colours.text)
            setTextColor(R.id.widget_artist, colours.subtext)
            setImageViewResource(R.id.widget_play_pause, if (lastPlaying) R.drawable.pause else R.drawable.play)
            setInt(R.id.widget_play_pause, "setColorFilter", colours.accent)
            setInt(R.id.widget_previous, "setColorFilter", colours.text)
            setInt(R.id.widget_next, "setColorFilter", colours.text)

            if (art != null) setImageViewBitmap(R.id.widget_artwork, art)
            else setImageViewResource(R.id.widget_artwork, R.drawable.placeholder_icon)

            val drawn = art?.let { background(context, it, look.background) }
            if (drawn != null) {
                setImageViewBitmap(R.id.widget_background, drawn)
                // A transparent filter, SRC_ATOP: leaves the picture exactly as drawn.
                setInt(R.id.widget_background, "setColorFilter", Color.TRANSPARENT)
            } else {
                setImageViewResource(R.id.widget_background, R.drawable.widget_background)
                setInt(R.id.widget_background, "setColorFilter", colours.background)
            }
            setImageViewBitmap(R.id.widget_seekbar, seekBar(context, lastFraction, look, colours))

            setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_artwork, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_previous, commandIntent(context, ACTION_PREVIOUS, 1))
            setOnClickPendingIntent(R.id.widget_play_pause, commandIntent(context, ACTION_PLAY_PAUSE, 2))
            setOnClickPendingIntent(R.id.widget_next, commandIntent(context, ACTION_NEXT, 3))
        }
        manager.updateAppWidget(ids, views)
    }

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Over a picture (frosted, blurred, gradient) the widget is white on dark, like the player. On
     * the theme style it takes the system's own Material You colours on Android 12+, the same
     * palette the app's dynamic theme comes from, and the app's dark grey before that.
     */
    private fun colours(context: Context, style: PlayerBackgroundStyle, hasArt: Boolean): Colours {
        val overPicture = hasArt && style != PlayerBackgroundStyle.FOLLOW_THEME
        if (overPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Colours(
                background = 0xE6202124.toInt(),
                text = Color.WHITE,
                subtext = 0xB3FFFFFF.toInt(),
                accent = Color.WHITE,
                track = 0x59FFFFFF,
            )
        }
        val night = isNight(context)
        fun c(id: Int) = context.getColor(id)
        val accent = c(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        return Colours(
            background = c(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50),
            text = c(if (night) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900),
            subtext = c(if (night) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700),
            accent = accent,
            track = ColorUtils.setAlphaComponent(accent, 0x59),
        )
    }

    /** The background picture for [style], or null when it is the plain theme colour. */
    private fun background(context: Context, art: Bitmap, style: PlayerBackgroundStyle): Bitmap? {
        if (style == PlayerBackgroundStyle.FOLLOW_THEME) return null
        val key = "${lastArtSongId}|$style|${isNight(context)}"
        if (key == lastBackgroundKey) lastBackground?.let { return it }

        val fill = Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fill)
        when (style) {
            PlayerBackgroundStyle.FROSTED, PlayerBackgroundStyle.BLUR -> {
                // A blur without RenderScript: the middle band of the cover shrunk to a handful of
                // pixels and stretched back up with filtering, twice so it comes out smooth.
                val band = Bitmap.createBitmap(art, 0, art.height / 3, art.width, art.height / 3)
                val tiny = Bitmap.createScaledBitmap(band, 24, 10, true)
                val mid = Bitmap.createScaledBitmap(tiny, 96, 40, true)
                canvas.drawBitmap(Bitmap.createScaledBitmap(mid, BG_W, BG_H, true), 0f, 0f, null)
                // Frosted is the pale pane of glass over the blur; plain blur is just darkened.
                if (style == PlayerBackgroundStyle.FROSTED) canvas.drawColor(0x26FFFFFF)
                canvas.drawColor(if (style == PlayerBackgroundStyle.FROSTED) 0x73000000 else 0x80000000.toInt())
            }

            else -> {
                // Gradient and Liquid: the cover's two sides, as a sweep, darkened enough for text.
                val sides = Bitmap.createScaledBitmap(art, 2, 1, true)
                val left = ColorUtils.blendARGB(sides.getPixel(0, 0), Color.BLACK, 0.45f)
                val right = ColorUtils.blendARGB(sides.getPixel(1, 0), Color.BLACK, 0.55f)
                val paint = Paint().apply {
                    shader = LinearGradient(0f, 0f, BG_W.toFloat(), BG_H.toFloat(), left, right, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, BG_W.toFloat(), BG_H.toFloat(), paint)
            }
        }

        // Rounded here, since a RemoteViews ImageView cannot clip itself before Android 12.
        val rounded = Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888)
        Canvas(rounded).drawRoundRect(
            RectF(0f, 0f, BG_W.toFloat(), BG_H.toFloat()), CORNER_PX, CORNER_PX,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(fill, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
        )
        lastBackground = rounded
        lastBackgroundKey = key
        return rounded
    }

    /**
     * The seek bar, drawn in the player's own style and thickness: the played part in the accent,
     * the rest a faint track. The waves are drawn still - a home screen is not the place for
     * something animating all day.
     */
    private fun seekBar(context: Context, fraction: Float, look: Look, colours: Colours): Bitmap {
        val bitmap = Bitmap.createBitmap(SEEK_W, SEEK_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cy = SEEK_H / 2f
        val played = SEEK_W * fraction.coerceIn(0f, 1f)
        val weight = look.weight.scale
        val minStroke = context.resources.displayMetrics.density
        fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = maxOf(width, minStroke)
            strokeCap = Paint.Cap.ROUND
        }
        fun wave(amplitude: Float, wavelength: Float, phase: Float, until: Float): Path = Path().apply {
            var x = 0f
            moveTo(0f, cy)
            while (x <= until) {
                lineTo(x, cy + amplitude * sin(2 * PI * x / wavelength + phase).toFloat())
                x += 3f
            }
        }

        val trackPaint = stroke(colours.track, SEEK_H * 0.12f * weight)
        val inset = trackPaint.strokeWidth
        if (played < SEEK_W - inset) canvas.drawLine(maxOf(played, inset), cy, SEEK_W - inset, cy, trackPaint)

        when (look.slider) {
            SliderStyle.DEFAULT -> {
                val active = stroke(colours.accent, SEEK_H * 0.28f * weight)
                if (played > inset) canvas.drawLine(inset, cy, played, cy, active)
                canvas.drawCircle(played.coerceIn(SEEK_H * 0.35f, SEEK_W - SEEK_H * 0.35f), cy, SEEK_H * 0.32f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colours.accent })
            }

            SliderStyle.SLIM -> {
                val active = stroke(colours.accent, SEEK_H * 0.14f * weight)
                if (played > inset) canvas.drawLine(inset, cy, played, cy, active)
            }

            SliderStyle.SQUIGGLY -> {
                canvas.drawPath(wave(SEEK_H * 0.22f, SEEK_H * 1.6f, 0f, played), stroke(colours.accent, SEEK_H * 0.16f * weight))
            }

            SliderStyle.WAVES -> {
                // Soft overlapping waves above the line, filled.
                for ((i, alpha) in listOf(0x66, 0x44, 0x2A).withIndex()) {
                    val path = wave(SEEK_H * (0.30f - i * 0.06f), SEEK_H * (2.2f + i * 0.7f), i * 1.3f, played)
                    path.lineTo(played, cy); path.lineTo(0f, cy); path.close()
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ColorUtils.setAlphaComponent(colours.accent, alpha)
                        style = Paint.Style.FILL
                    })
                }
                if (played > inset) canvas.drawLine(inset, cy, played, cy, stroke(colours.accent, SEEK_H * 0.08f * weight))
            }

            SliderStyle.WAVE_LINES -> {
                // The same three waves, as fine lines a shade apart.
                for ((i, alpha) in listOf(0xFF, 0xAA, 0x70).withIndex()) {
                    canvas.drawPath(
                        wave(SEEK_H * (0.30f - i * 0.06f), SEEK_H * (2.2f + i * 0.7f), i * 1.3f, played),
                        stroke(ColorUtils.setAlphaComponent(colours.accent, alpha), SEEK_H * 0.07f * weight),
                    )
                }
            }
        }
        return bitmap
    }

    private suspend fun loadArt(context: Context, metadata: MediaMetadata): Bitmap? {
        val model = metadata.getThumbnailModel(ART_PX, ART_PX) ?: return null
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .size(ART_PX, ART_PX)
                // A hardware bitmap cannot be parcelled into RemoteViews, or drawn on a Canvas.
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
