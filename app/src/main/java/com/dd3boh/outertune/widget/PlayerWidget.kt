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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
import com.dd3boh.outertune.constants.LiquidColorScheme
import com.dd3boh.outertune.constants.LiquidColorSchemeKey
import com.dd3boh.outertune.constants.LiquidShapeStyle
import com.dd3boh.outertune.constants.LiquidShapeStyleKey
import com.dd3boh.outertune.constants.MediaSessionConstants
import com.dd3boh.outertune.constants.WidgetLayoutKey
import com.dd3boh.outertune.constants.WidgetLayoutMediumKey
import com.dd3boh.outertune.constants.WidgetLayoutSmallKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.SliderWeight
import com.dd3boh.outertune.constants.SliderWeightKey
import com.dd3boh.outertune.constants.SliderDotKey
import com.dd3boh.outertune.ui.component.drawPlayerSliderStill
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import kotlin.math.PI
import kotlin.math.roundToInt
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

    /** Resized: what fits has changed - controls only, compact, or everything. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        PlayerWidget.pushLast(context, appWidgetManager, intArrayOf(appWidgetId))
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
                // The notification's own commands, so the widget shuffles and repeats exactly the
                // way the notification buttons do (the app's queue shuffle, not just the player's).
                PlayerWidget.ACTION_SHUFFLE ->
                    controller.sendCustomCommand(MediaSessionConstants.CommandToggleShuffle, Bundle.EMPTY)
                PlayerWidget.ACTION_REPEAT ->
                    controller.sendCustomCommand(MediaSessionConstants.CommandToggleRepeatMode, Bundle.EMPTY)
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

    /**
     * Every update carries its pictures inside it, and Android caps a single update at about 1MB.
     * The pictures without transparency (cover, background) are stored at 16 bits a pixel, which is
     * what lets them be sharp and still fit: a 224px cover for a 64dp slot, and a background 480px
     * on its long side, drawn in the widget's own shape so nothing in it is stretched.
     */
    private const val ART_PX = 224
    private const val BG_LONG_PX = 480
    private const val ICON_PX = 80

    /** Corner radius, before Android 12, where the corners have to be drawn into the pictures. */
    private const val CORNER_DP = 24f

    private val roundsItself = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** How much of what the widget can show fits, from its size - see [sizeOf]. */
    private enum class WidgetSize { CONTROLS, COMPACT, FULL }

    private val CONTROL_ELEMENTS = setOf(
        WidgetElement.SHUFFLE, WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT, WidgetElement.REPEAT,
    )
    private val CORE_CONTROLS = setOf(WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT)

    /** What the player looks like, as far as the widget copies it. */
    private data class Look(
        val background: PlayerBackgroundStyle,
        val slider: SliderStyle,
        val weight: SliderWeight,
        val liquidShape: LiquidShapeStyle = LiquidShapeStyle.PETAL,
        val liquidBackdrop: LiquidColorScheme = LiquidColorScheme.SURFACE,
        val dot: Boolean = false,
    )

    /** The colours one look resolves to - see [colours]. */
    private data class Colours(val background: Int, val text: Int, val subtext: Int, val accent: Int, val track: Int)

    @Volatile private var lastTitle: String? = null
    @Volatile private var lastArtist: String? = null
    @Volatile private var lastPlaying = false
    @Volatile private var lastFraction = 0f
    @Volatile private var lastArt: Bitmap? = null
    @Volatile private var lastArtSongId: String? = null
    @Volatile private var lastLook = Look(DEFAULT_PLAYER_BACKGROUND, DEFAULT_SLIDER_STYLE, SliderWeight.THINNER)

    @Volatile private var lastShuffle = false
    @Volatile private var lastRepeat = Player.REPEAT_MODE_OFF
    @Volatile private var lastUpNext: String? = null
    @Volatile private var lastArtistArt: Bitmap? = null
    @Volatile private var lastArtistArtUrl: String? = null
    @Volatile private var lastLayout = WidgetLayout.DEFAULT

    /** Arrangements set for the smaller sizes in the editor, or null to cut them from [lastLayout]. */
    @Volatile private var lastSmall: WidgetLayout? = null
    @Volatile private var lastMedium: WidgetLayout? = null
    @Volatile private var lastLyrics: WidgetLyrics.Frame? = null

    /** Whether any placed widget's arrangement shows lyrics - the service only works them out if so. */
    val wantsLyrics: Boolean get() = allLayouts().any { WidgetElement.LYRICS in it }

    private fun allLayouts() = listOfNotNull(lastLayout, lastSmall, lastMedium)

    /** The lyrics around the line now being sung, or null for none. Shown at the next update. */
    fun setLyrics(frame: WidgetLyrics.Frame?) {
        lastLyrics = frame
    }

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
    suspend fun update(
        context: Context,
        metadata: MediaMetadata?,
        playing: Boolean,
        fraction: Float,
        shuffle: Boolean = false,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        upNext: String? = null,
        artistImageUrl: String? = null,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context, manager)

        lastTitle = metadata?.title
        lastArtist = metadata?.artists?.joinToString { it.name }
        lastPlaying = playing
        lastFraction = fraction
        lastShuffle = shuffle
        lastRepeat = repeatMode
        lastUpNext = upNext
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
            dot = prefs[SliderDotKey] ?: false,
            liquidShape = prefs[LiquidShapeStyleKey]?.let { stored -> LiquidShapeStyle.entries.find { it.name == stored } }
                ?: LiquidShapeStyle.PETAL,
            liquidBackdrop = prefs[LiquidColorSchemeKey]?.let { stored -> LiquidColorScheme.entries.find { it.name == stored } }
                ?: LiquidColorScheme.SURFACE,
        )
        lastLayout = WidgetLayout.parse(prefs[WidgetLayoutKey])
        lastSmall = prefs[WidgetLayoutSmallKey]?.takeIf { it.isNotBlank() }?.let { WidgetLayout.parse(it, WidgetLayout.SMALL_ROWS) }
        lastMedium = prefs[WidgetLayoutMediumKey]?.takeIf { it.isNotBlank() }?.let { WidgetLayout.parse(it, WidgetLayout.MEDIUM_ROWS) }

        // Text and buttons straight away; the cover, and the background drawn from it, follow.
        pushLast(context, manager, ids)
        var changed = false
        if (metadata != null && lastArt == null) {
            val art = loadArt(context, metadata.getThumbnailModel(ART_PX, ART_PX))
            if (art != null && lastArtSongId == metadata.id) {
                lastArt = art
                changed = true
            }
        }
        // The artist's own picture, only when the arrangement shows it.
        val wantsArtist = allLayouts().any { WidgetElement.ARTIST in it }
        if (wantsArtist && artistImageUrl != lastArtistArtUrl) {
            lastArtistArtUrl = artistImageUrl
            lastArtistArt = artistImageUrl?.let { loadArt(context, it) }
            changed = true
        }
        if (changed) pushLast(context, manager, ids)
    }

    /**
     * Moves the seek bar while playing.
     *
     * A whole update rather than a partial one. A partial update - only the seek bar's picture - is
     * the cheaper call, but launchers are free to drop them, and some (HyperOS's among them) do: the
     * bar sat at the very start of the song, which in the wave styles is just a flat line. Skipped
     * while the screen is off, when there is nobody to see it move.
     */
    fun updateProgress(context: Context, fraction: Float) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids(context, manager)
        if (ids.isEmpty()) return
        lastFraction = fraction
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power?.isInteractive == false) return
        if (allLayouts().none { WidgetElement.SEEK_BAR in it || WidgetElement.LYRICS in it }) return
        pushLast(context, manager, ids)
    }

    /**
     * How much fits, from the size the launcher reports (portrait: minimum width, maximum height).
     * One cell tall is room for the controls alone; two for the cover, the title and the controls;
     * three or more for the whole arrangement.
     */
    private data class Fit(val size: WidgetSize, val widthDp: Int, val heightDp: Int)

    private fun sizeOf(manager: AppWidgetManager, id: Int): Fit {
        val options = manager.getAppWidgetOptions(id)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val size = when {
            heightDp <= 0 -> WidgetSize.FULL
            heightDp < 100 -> WidgetSize.CONTROLS
            heightDp < 190 -> WidgetSize.COMPACT
            else -> WidgetSize.FULL
        }
        return Fit(size, widthDp.takeIf { it > 0 } ?: 300, heightDp.takeIf { it > 0 } ?: 400)
    }

    /**
     * What a widget of [size] shows. The small sizes are cut from the user's own arrangement - its
     * controls, in its order, and its cover if it has one - rather than being fixed, so they follow
     * the editor. Narrow widgets keep only previous, play and next, which is what fits two cells.
     */
    private fun layoutFor(size: WidgetSize, widthDp: Int, heightDp: Int): WidgetLayout {
        val all = lastLayout.elements
        val chosen = all.filter { it in CONTROL_ELEMENTS }.ifEmpty { listOf(WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT) }
        val controls = if (widthDp < 250) {
            chosen.filter { it in CORE_CONTROLS }.ifEmpty { listOf(WidgetElement.PLAY_PAUSE) }
        } else chosen
        return when (size) {
            // Set in the editor's Small / Medium tabs if they were; otherwise cut from the large one.
            WidgetSize.CONTROLS -> lastSmall ?: WidgetLayout.fromRows(listOf(controls), WidgetLayout.SMALL_ROWS)
            WidgetSize.COMPACT -> lastMedium ?: WidgetLayout.fromRows(
                listOf(listOfNotNull(WidgetElement.COVER.takeIf { it in all }, WidgetElement.INFO), listOf(WidgetElement.SEEK_BAR), controls),
                WidgetLayout.MEDIUM_ROWS,
            )
            WidgetSize.FULL -> {
                // Lyrics step aside while the song has no line to show; anything taller than the
                // widget is shed in DROP_ORDER below, so lyrics stay whenever they fit.
                // The grid closes up the
                // rows they leave empty.
                val drop = buildSet {
                    if (lastLyrics == null) add(WidgetElement.LYRICS)
                }
                val kept = lastLayout.placements.filter { it.element !in drop }
                if (kept.isEmpty()) lastLayout else lastLayout.copy(placements = kept).normalised()
            }
        }
    }

    internal fun pushLast(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // One build per widget: each can be a different size, and so show different things.
        ids.forEach { id -> pushTo(context, manager, id) }
    }

    /** Pieces given up, in this order, when a widget is too short for everything it was given. */
    private val DROP_ORDER = listOf(
        WidgetElement.UP_NEXT, WidgetElement.ARTIST, WidgetElement.LYRICS, WidgetElement.SEEK_BAR,
        WidgetElement.SHUFFLE, WidgetElement.REPEAT,
    )

    private fun pushTo(context: Context, manager: AppWidgetManager, id: Int) {
        val art = lastArt
        val look = lastLook
        val colours = colours(context, look.background, art != null)
        val (size, widthDp, heightDp) = sizeOf(manager, id)
        var layout = layoutFor(size, widthDp, heightDp)

        // Fit the height: smaller buttons first, then a smaller cover (coverSize), then - only if it
        // still does not fit - the least important pieces go. Nothing is left to be cut off by the
        // launcher, which is what was hiding the cover.
        var small = widthDp < 200 || heightDp < 90
        var coverDp = WidgetMetrics.coverSize(layout, widthDp, heightDp, small)
        if (!small && WidgetMetrics.contentHeight(layout, false, coverDp, heightDp) > heightDp) {
            small = true
            coverDp = WidgetMetrics.coverSize(layout, widthDp, heightDp, true)
        }
        for (element in DROP_ORDER) {
            if (WidgetMetrics.contentHeight(layout, small, coverDp, heightDp) <= heightDp) break
            if (element in layout && layout.placements.size > 1) {
                layout = layout.remove(element)
                coverDp = WidgetMetrics.coverSize(layout, widthDp, heightDp, small)
            }
        }

        val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
            val drawn = art?.let { background(context, it, look.background, widthDp, heightDp) }
            if (drawn != null) {
                setImageViewBitmap(R.id.widget_background, drawn)
                // A transparent filter, SRC_ATOP: leaves the picture exactly as drawn.
                setInt(R.id.widget_background, "setColorFilter", Color.TRANSPARENT)
            } else {
                setImageViewResource(R.id.widget_background, R.drawable.widget_background)
                setInt(R.id.widget_background, "setColorFilter", colours.background)
            }
            setOnClickPendingIntent(android.R.id.background, openAppIntent(context))
            val pad = (WidgetMetrics.padding(heightDp) * context.resources.displayMetrics.density).roundToInt()
            setViewPadding(R.id.widget_rows, pad, pad, pad, pad)

            removeAllViews(R.id.widget_rows)
            // Each grid row as a line of cells: a gap cell up to the next piece, the piece in a cell
            // as wide as its span, and a gap to the end - every column accounted for, so the
            // widths come out exactly as placed in the editor.
            layout.placements.groupBy { it.row }.toSortedMap().values.forEach { rowPlacements ->
                val rowViews = RemoteViews(context.packageName, R.layout.widget_row)
                var col = 0
                rowPlacements.sortedBy { it.col }.forEach { placement ->
                    if (placement.col > col) rowViews.addView(R.id.widget_row_container, cell(context, placement.col - col))
                    val cellViews = cell(context, placement.span)
                    cellViews.addView(
                        R.id.el_cell,
                        elementView(context, placement.element, colours, look, widthDp * placement.span / layout.columns, small, coverDp),
                    )
                    rowViews.addView(R.id.widget_row_container, cellViews)
                    col = placement.end
                }
                if (col < layout.columns) rowViews.addView(R.id.widget_row_container, cell(context, layout.columns - col))
                addView(R.id.widget_rows, rowViews)
            }
        }
        runCatching { manager.updateAppWidget(id, views) }
    }

    /** One element of the arrangement, filled in - see [WidgetElement] and the widget_el_* layouts. */
    private fun elementView(
        context: Context,
        element: WidgetElement,
        colours: Colours,
        look: Look,
        widthDp: Int,
        small: Boolean,
        coverDp: Int,
    ): RemoteViews {
        // The app's own rounded Material icons, drawn to bitmaps, so the widget's buttons are the
        // player's buttons rather than look-alikes from a different icon set.
        // A fine grid can give a button a narrow cell; the smaller button fits one.
        val narrow = small || widthDp < 52
        fun button(icon: ImageVector, action: String, requestCode: Int, tint: Int, big: Boolean = false) =
            RemoteViews(
                context.packageName,
                when {
                    big && narrow -> R.layout.widget_el_play_small
                    big -> R.layout.widget_el_play
                    narrow -> R.layout.widget_el_button_small
                    else -> R.layout.widget_el_button
                },
            ).apply {
                setImageViewBitmap(R.id.el_button, iconBitmap(icon, tint))
                setOnClickPendingIntent(R.id.el_button, commandIntent(context, action, requestCode))
            }
        return when (element) {
            // Sized to fit (see WidgetMetrics.coverSize). Android 12+ sizes the view exactly and rounds
            // it with its outline; before that there are two fixed sizes and the corners are drawn in.
            WidgetElement.COVER -> RemoteViews(
                context.packageName,
                if (roundsItself || coverDp >= 56) R.layout.widget_el_cover else R.layout.widget_el_cover_small,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setViewLayoutWidth(R.id.el_image, coverDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.el_image, coverDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                }
                lastArt?.let { setImageViewBitmap(R.id.el_image, if (roundsItself) opaque(it) else rounded(it, 0.16f)) }
                setOnClickPendingIntent(R.id.el_image, openAppIntent(context))
            }

            WidgetElement.ARTIST -> RemoteViews(context.packageName, R.layout.widget_el_artist).apply {
                val size = WidgetMetrics.heightOf(WidgetElement.ARTIST, small, coverDp).toFloat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setViewLayoutWidth(R.id.el_image, size, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(R.id.el_image, size, TypedValue.COMPLEX_UNIT_DIP)
                }
                (lastArtistArt ?: lastArt)?.let { setImageViewBitmap(R.id.el_image, if (roundsItself) opaque(it) else rounded(it, 0.5f)) }
                setOnClickPendingIntent(R.id.el_image, openAppIntent(context))
            }

            WidgetElement.INFO -> RemoteViews(context.packageName, R.layout.widget_el_info).apply {
                setTextViewText(R.id.el_title, lastTitle ?: context.getString(R.string.widget_nothing_playing))
                setTextViewText(R.id.el_subtitle, lastArtist.orEmpty())
                setTextColor(R.id.el_title, colours.text)
                setTextColor(R.id.el_subtitle, colours.subtext)
            }

            WidgetElement.UP_NEXT -> RemoteViews(context.packageName, R.layout.widget_el_upnext).apply {
                setTextViewText(
                    R.id.el_text,
                    lastUpNext?.let { context.getString(R.string.widget_up_next, it) } ?: "",
                )
                setTextColor(R.id.el_text, colours.subtext)
            }

            WidgetElement.SEEK_BAR -> RemoteViews(context.packageName, R.layout.widget_el_seekbar).apply {
                // Drawn at the bar's real size on this widget - the padding taken off its width - so
                // the waves are not stretched or squashed into shape by the image view.
                val density = context.resources.displayMetrics.density
                val widthPx = ((widthDp - 24).coerceAtLeast(80) * density).roundToInt()
                val heightPx = (24 * density).roundToInt()
                setImageViewBitmap(R.id.el_seekbar, seekBar(context, lastFraction, look, colours, widthPx, heightPx))
            }

            WidgetElement.LYRICS -> RemoteViews(context.packageName, R.layout.widget_el_lyrics).apply {
                val frame = lastLyrics
                val faint = ColorUtils.setAlphaComponent(colours.text, 0x73)
                // The words sung so far in the text colour, the rest of the line dimmed - spans travel
                // with the text through RemoteViews, so this needs no picture.
                val current = SpannableString(frame?.current.orEmpty()).apply {
                    val sung = (frame?.sungChars ?: 0).coerceIn(0, length)
                    if (sung > 0) setSpan(ForegroundColorSpan(colours.text), 0, sung, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (sung < length) setSpan(ForegroundColorSpan(faint), sung, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setTextViewText(R.id.el_text, current)
                setTextColor(R.id.el_text, colours.text)
                setTextViewText(R.id.el_prev, frame?.previous.orEmpty())
                setTextViewText(R.id.el_next, frame?.next.orEmpty())
                setTextColor(R.id.el_prev, faint)
                setTextColor(R.id.el_next, faint)
                setViewVisibility(R.id.el_prev, if (frame?.previous.isNullOrBlank()) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.el_next, if (frame?.next.isNullOrBlank()) View.GONE else View.VISIBLE)
            }

            WidgetElement.PREVIOUS -> button(Icons.Rounded.SkipPrevious, ACTION_PREVIOUS, 1, colours.text)
            WidgetElement.PLAY_PAUSE -> button(
                if (lastPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, ACTION_PLAY_PAUSE, 2, colours.accent,
                big = true,
            )
            WidgetElement.NEXT -> button(Icons.Rounded.SkipNext, ACTION_NEXT, 3, colours.text)
            // Lit in the accent while on, dimmed while off - the player's own buttons.
            WidgetElement.SHUFFLE -> button(
                Icons.Rounded.Shuffle, ACTION_SHUFFLE, 4,
                if (lastShuffle) colours.accent else ColorUtils.setAlphaComponent(colours.text, 0x80),
            )
            WidgetElement.REPEAT -> button(
                if (lastRepeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                ACTION_REPEAT, 5,
                if (lastRepeat != Player.REPEAT_MODE_OFF) colours.accent else ColorUtils.setAlphaComponent(colours.text, 0x80),
            )
        }
    }

    private val CELL_LAYOUTS = intArrayOf(
        R.layout.widget_cell_1, R.layout.widget_cell_2, R.layout.widget_cell_3, R.layout.widget_cell_4,
        R.layout.widget_cell_5, R.layout.widget_cell_6, R.layout.widget_cell_7, R.layout.widget_cell_8,
        R.layout.widget_cell_9, R.layout.widget_cell_10, R.layout.widget_cell_11, R.layout.widget_cell_12,
        R.layout.widget_cell_13, R.layout.widget_cell_14, R.layout.widget_cell_15, R.layout.widget_cell_16,
    )

    /** A grid cell [span] columns wide - see the widget_cell_* layouts. */
    private fun cell(context: Context, span: Int) =
        RemoteViews(context.packageName, CELL_LAYOUTS[span.coerceIn(1, WidgetLayout.MAX_COLUMNS) - 1])

    /** Drawn icons, kept per icon and colour - the same few are redrawn on every update otherwise. */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /** [vector] drawn [ICON_PX] square in [colour] - the Compose icon itself, path for path. */
    private fun iconBitmap(vector: ImageVector, colour: Int): Bitmap {
        val key = "${vector.name}|$colour"
        iconCache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(ICON_PX / vector.viewportWidth, ICON_PX / vector.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            style = Paint.Style.FILL
        }
        fun draw(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorPath -> {
                        val path = node.pathData.toPath().asAndroidPath()
                        path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                            android.graphics.Path.FillType.EVEN_ODD
                        } else {
                            android.graphics.Path.FillType.WINDING
                        }
                        canvas.drawPath(path, paint)
                    }
                    is VectorGroup -> draw(node)
                }
            }
        }
        draw(vector.root)
        if (iconCache.size > 64) iconCache.clear()
        iconCache[key] = bitmap
        return bitmap
    }

    /** [source] at 16 bits a pixel - half the size in an update, for pictures with no transparency. */
    private val opaqueCache = java.util.WeakHashMap<Bitmap, Bitmap>()
    private fun opaque(source: Bitmap): Bitmap = synchronized(opaqueCache) {
        opaqueCache.getOrPut(source) { source.copy(Bitmap.Config.RGB_565, false) ?: source }
    }

    /** [source] with its corners rounded by [fraction] of its shorter side; 0.5 is a circle. */
    private fun rounded(source: Bitmap, fraction: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val radius = minOf(source.width, source.height) * fraction
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
        )
        return out
    }

    /**
     * Applies a new arrangement from the widget editor straight away, without waiting for the next
     * song or play-state change to redraw.
     */
    fun applyLayout(context: Context, layout: WidgetLayout, baseRows: Int) {
        when (baseRows) {
            WidgetLayout.SMALL_ROWS -> lastSmall = layout
            WidgetLayout.MEDIUM_ROWS -> lastMedium = layout
            else -> lastLayout = layout
        }
        val manager = AppWidgetManager.getInstance(context)
        pushLast(context, manager, ids(context, manager))
    }

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Over a picture (frosted, blurred, gradient) the widget is white on dark, like the player. On
     * the theme style it takes the system's own Material You colours on Android 12+, the same
     * palette the app's dynamic theme comes from, and the app's dark grey before that.
     */
    private fun colours(context: Context, style: PlayerBackgroundStyle, hasArt: Boolean): Colours {
        // Liquid on its white backdrop is the one picture that needs dark text.
        if (hasArt && style == PlayerBackgroundStyle.LIQUID && lastLook.liquidBackdrop == LiquidColorScheme.WHITE) {
            return Colours(
                background = Color.WHITE,
                text = 0xFF1B1B1F.toInt(),
                subtext = 0xB31B1B1F.toInt(),
                accent = 0xFF1B1B1F.toInt(),
                track = 0x401B1B1F,
            )
        }
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

    /**
     * The background picture for [style], or null when it is the plain theme colour.
     *
     * Drawn in the widget's own shape - [widthDp] by [heightDp], scaled so its long side is
     * [BG_LONG_PX] - rather than one fixed wide strip the image view then stretched: on a square or
     * tall widget that pulled a Liquid shape or a gradient out of shape and blurred every edge.
     */
    @Suppress("LocalVariableName")
    private fun background(context: Context, art: Bitmap, style: PlayerBackgroundStyle, widthDp: Int, heightDp: Int): Bitmap? {
        if (style == PlayerBackgroundStyle.FOLLOW_THEME) return null
        val aspect = widthDp.toFloat() / heightDp.coerceAtLeast(1)
        val BG_W = if (aspect >= 1f) BG_LONG_PX else (BG_LONG_PX * aspect).roundToInt().coerceAtLeast(60)
        val BG_H = if (aspect >= 1f) (BG_LONG_PX / aspect).roundToInt().coerceAtLeast(60) else BG_LONG_PX
        val key = "${lastArtSongId}|$style|${lastLook.liquidShape}|${lastLook.liquidBackdrop}|${isNight(context)}|${BG_W}x$BG_H"
        if (key == lastBackgroundKey) lastBackground?.let { return it }

        val fill = Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fill)
        when (style) {
            PlayerBackgroundStyle.FROSTED, PlayerBackgroundStyle.BLUR -> {
                // A blur without RenderScript: a band of the cover in the widget's shape, shrunk to a
                // handful of pixels and stretched back up with filtering, twice so it comes out smooth.
                val bandH = (art.width * BG_H / BG_W).coerceIn(1, art.height)
                val band = Bitmap.createBitmap(art, 0, (art.height - bandH) / 2, art.width, bandH)
                val tinyW = 24
                val tinyH = (tinyW * BG_H / BG_W).coerceAtLeast(4)
                val tiny = Bitmap.createScaledBitmap(band, tinyW, tinyH, true)
                val mid = Bitmap.createScaledBitmap(tiny, tinyW * 4, tinyH * 4, true)
                canvas.drawBitmap(Bitmap.createScaledBitmap(mid, BG_W, BG_H, true), 0f, 0f, null)
                // Frosted is the pale pane of glass over the blur; plain blur is just darkened.
                if (style == PlayerBackgroundStyle.FROSTED) canvas.drawColor(0x26FFFFFF)
                canvas.drawColor(if (style == PlayerBackgroundStyle.FROSTED) 0x73000000 else 0x80000000.toInt())
            }

            PlayerBackgroundStyle.LIQUID -> drawLiquid(context, canvas, art, BG_W, BG_H)

            else -> {
                // Gradient: the cover's two sides, as a sweep, darkened enough for text.
                val sides = Bitmap.createScaledBitmap(art, 2, 1, true)
                val left = ColorUtils.blendARGB(sides.getPixel(0, 0), Color.BLACK, 0.45f)
                val right = ColorUtils.blendARGB(sides.getPixel(1, 0), Color.BLACK, 0.55f)
                val paint = Paint().apply {
                    shader = LinearGradient(0f, 0f, BG_W.toFloat(), BG_H.toFloat(), left, right, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, BG_W.toFloat(), BG_H.toFloat(), paint)
            }
        }

        val result = if (roundsItself) {
            // Android 12+: the widget's outline cuts the corners at screen resolution (see
            // widget_player), so the picture stays square and opaque - and 16-bit, at half the size.
            fill.copy(Bitmap.Config.RGB_565, false) ?: fill
        } else {
            // Before that, the corners are drawn in: the widget's radius in this picture's pixels.
            val radius = CORNER_DP * BG_W / widthDp.coerceAtLeast(1)
            Bitmap.createBitmap(BG_W, BG_H, Bitmap.Config.ARGB_8888).also { rounded ->
                Canvas(rounded).drawRoundRect(
                    RectF(0f, 0f, BG_W.toFloat(), BG_H.toFloat()), radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(fill, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
                )
            }
        }
        lastBackground = result
        lastBackgroundKey = key
        return result
    }

    /**
     * The Liquid background, as a still: the same backdrop and the same shape as the player - petal,
     * spheres or ferrofluid crown - frozen at one moment rather than moving, since a home screen is
     * no place for an animation running all day. Drawn off to the right, behind the buttons, so the
     * cover and the title stay readable.
     */
    @Suppress("LocalVariableName")
    private fun drawLiquid(context: Context, canvas: Canvas, art: Bitmap, BG_W: Int, BG_H: Int) {
        val night = isNight(context)
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        fun sys(dark: Int, light: Int) = context.getColor(if (night) dark else light)
        // The cover's own colours, for the spheres and as the accent where the system has none.
        val sides = Bitmap.createScaledBitmap(art, 3, 1, true)
        val coverColours = List(3) { sides.getPixel(it, 0) }
        val accent = if (dynamic) sys(android.R.color.system_accent1_300, android.R.color.system_accent1_500) else coverColours[1]
        val secondary = if (dynamic) sys(android.R.color.system_accent2_300, android.R.color.system_accent2_500) else coverColours[0]
        val backdrop = when (lastLook.liquidBackdrop) {
            LiquidColorScheme.BLACK -> Color.BLACK
            LiquidColorScheme.WHITE -> Color.WHITE
            LiquidColorScheme.SURFACE ->
                if (dynamic) sys(android.R.color.system_accent1_800, android.R.color.system_accent1_200)
                else ColorUtils.blendARGB(coverColours[1], Color.BLACK, 0.7f)
        }
        canvas.drawColor(backdrop)

        val cx = BG_W * 0.72f
        val cy = BG_H * 0.5f
        val base = BG_H * 0.42f
        when (lastLook.liquidShape) {
            LiquidShapeStyle.SPHERES -> {
                coverColours.forEachIndexed { i, colour ->
                    val x = BG_W * (0.55f + 0.16f * i)
                    val y = BG_H * (0.35f + 0.22f * ((i * 7) % 3) / 2f)
                    val r = base * (0.9f + 0.25f * i)
                    canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = android.graphics.RadialGradient(
                            x, y, r,
                            intArrayOf(ColorUtils.setAlphaComponent(colour, 0xD9), ColorUtils.setAlphaComponent(colour, 0x60), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP,
                        )
                    })
                }
            }

            LiquidShapeStyle.PETAL -> {
                val petal = Path()
                val samples = 96
                for (i in 0..samples) {
                    val a = 2 * PI * i / samples
                    val r = base * (1f + 0.12f * sin(a * 3).toFloat() + 0.16f * sin(a * 5 + 1.3).toFloat())
                    val x = cx + r * kotlin.math.cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) petal.moveTo(x, y) else petal.lineTo(x, y)
                }
                petal.close()
                canvas.drawPath(petal, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(
                        cx, cy - base * 0.2f, base * 1.6f,
                        intArrayOf(ColorUtils.blendARGB(accent, secondary, 0.35f), accent, ColorUtils.setAlphaComponent(accent, 0)),
                        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                    )
                })
            }

            LiquidShapeStyle.FERROFLUID -> {
                // The crown: a dark pool with sharp spikes standing up around it, and a wet highlight.
                val spikes = 16
                val pool = base * 0.55f
                val crown = Path()
                for (i in 0 until spikes) {
                    val peakA = 2 * PI * i / spikes
                    val valleyA = 2 * PI * (i + 0.5) / spikes
                    val variance = 0.75f + 0.5f * (0.5f + 0.5f * sin(i * 12.9898).toFloat())
                    val peak = pool * (1.15f + 0.4f * variance) * 1.3f
                    val px = cx + peak * kotlin.math.cos(peakA).toFloat()
                    val py = cy + peak * sin(peakA).toFloat()
                    val vx = cx + pool * 0.9f * kotlin.math.cos(valleyA).toFloat()
                    val vy = cy + pool * 0.9f * sin(valleyA).toFloat()
                    if (i == 0) crown.moveTo(px, py) else crown.lineTo(px, py)
                    crown.lineTo(vx, vy)
                }
                crown.close()
                val ink = ColorUtils.blendARGB(Color.BLACK, accent, 0.18f)
                canvas.drawPath(crown, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(
                        cx, cy - pool * 0.3f, pool * 3f,
                        intArrayOf(ColorUtils.blendARGB(ink, Color.WHITE, 0.12f), ink, Color.BLACK),
                        null, Shader.TileMode.CLAMP,
                    )
                })
                canvas.save()
                canvas.clipPath(crown)
                val gx = cx - pool * 0.6f
                val gy = cy - pool * 0.7f
                canvas.drawCircle(gx, gy, pool * 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(
                        gx, gy, pool * 1.6f,
                        intArrayOf(ColorUtils.setAlphaComponent(accent, 0x80), Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP,
                    )
                })
                canvas.restore()
            }
        }
        // Keep the left side, where the cover and the title sit, a touch darker or lighter for contrast.
        val scrim = if (lastLook.liquidBackdrop == LiquidColorScheme.WHITE) 0x33FFFFFF else 0x40000000
        canvas.drawRect(0f, 0f, BG_W * 0.5f, BG_H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, BG_W * 0.5f, 0f, scrim, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
    }

    /**
     * The seek bar, drawn by the player's own code - [drawPlayerSliderStill] - so it is the same
     * style, thickness, waves and dot as the player's, not an imitation of them. Drawn still: a
     * home screen is not the place for something animating all day.
     */
    private fun seekBar(context: Context, fraction: Float, look: Look, colours: Colours, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        CanvasDrawScope().draw(
            Density(context),
            if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(Canvas(bitmap)),
            Size(widthPx.toFloat(), heightPx.toFloat()),
        ) {
            drawPlayerSliderStill(
                style = look.slider,
                fraction = fraction,
                active = androidx.compose.ui.graphics.Color(colours.accent),
                inactive = androidx.compose.ui.graphics.Color(colours.track),
                weight = look.weight.scale,
                dot = look.dot,
                playing = lastPlaying,
            )
        }
        return bitmap
    }

    private suspend fun loadArt(context: Context, model: Any?): Bitmap? {
        if (model == null) return null
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
    internal const val ACTION_SHUFFLE = "com.dd3boh.outertune.widget.SHUFFLE"
    internal const val ACTION_REPEAT = "com.dd3boh.outertune.widget.REPEAT"
    internal val COMMANDS = setOf(ACTION_PREVIOUS, ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_SHUFFLE, ACTION_REPEAT)
}
