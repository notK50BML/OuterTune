/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * OuterTune's own icons, taken from the Android app's vector drawables.
 *
 * The path data is lifted verbatim from the app's `res/drawable` vectors, so these are the same
 * shapes the phone app draws rather than lookalikes from an icon set. That matters for the transport
 * controls in particular: OuterTune's play/pause/skip glyphs are not Material's, and substituting
 * Material's would make the desktop build look like a different app wearing the name.
 *
 * Built as [ImageVector]s from path strings rather than by adding an icon library. The obvious
 * alternative, `material-icons-extended`, is around 11MB for a handful of glyphs - against a 42MB
 * total that is a quarter of the app's size spent on icons that would be *less* correct than these.
 * Android's XML vector format cannot be read directly off Android, but the only part that carries
 * meaning is the path, and [PathParser] parses exactly that.
 *
 * All of these use a 960x960 viewport, which is what the drawables use; the one drawable that did
 * not ([shuffleOff] in the app is a stroked 24x24 icon) is drawn here from the same 960 grid as its
 * "on" counterpart so the pair match in weight when placed side by side.
 */
object OuterTuneIcons {

    /**
     * Every path string, by the name it was given.
     *
     * Declared before the icons, and that is not style: an object's initialisers run top to bottom,
     * so a map declared below the first `by icon(...)` would still be null when that call tried to
     * record into it. The failure is a NullPointerException during class initialisation, which
     * surfaces as the whole screen refusing to draw.
     */
    private val paths = LinkedHashMap<String, String>()

    val play by icon(GeneratedIconPaths.play)

    val pause by icon(GeneratedIconPaths.pause)

    val skipNext by icon(GeneratedIconPaths.skipNext)

    val skipPrevious by icon(GeneratedIconPaths.skipPrevious)

    val shuffle by icon(GeneratedIconPaths.shuffle)

    val repeat by icon(GeneratedIconPaths.repeat)

    val repeatOne by icon(GeneratedIconPaths.repeatOne)

    val favorite by icon(GeneratedIconPaths.favorite)

    val favoriteBorder by icon(GeneratedIconPaths.favoriteBorder)

    val close by icon(GeneratedIconPaths.close)

    val queueMusic by icon(GeneratedIconPaths.queueMusic)

    /**
     * Two triangles, no bar - the same glyphs Android reaches for with `Icons.Rounded.FastRewind`
     * and `FastForward` on the seek buttons either side of play.
     *
     * Drawn on the 960 grid rather than copied from a drawable, because the app has no drawable for
     * these - it uses the Material set. Each triangle is the one already in [skipNext], so the pair
     * match the rest of the transport row in weight, and the geometry is checked by test: the group
     * spans 100..860 horizontally and 230..730 vertically, both centred on 480.
     */
    val fastForward by icon(GeneratedIconPaths.fastForward)

    val fastRewind by icon(GeneratedIconPaths.fastRewind)

    /**
     * Three bars of different heights, standing on a common baseline.
     *
     * Bottom-aligned rather than centred individually, because an equaliser's bars rise from a floor
     * - centring each one would read as a bar chart of nothing in particular. The group spans
     * 160..800 in both directions, so it sits centred in the viewport like every other icon here.
     */
    val equalizer by icon(GeneratedIconPaths.equalizer)

    /**
     * A single chevron pointing up, for the queue's own handle.
     *
     * One stroke, not two. The double chevron is Material's "expand less, all the way" and reads as
     * a jump to the end; the queue opens in stages, so a single mark is the honest one.
     *
     * Material's expand_less, scaled about its own centre and lifted onto the grid centre: the
     * stock glyph is a 480x296 mark sitting at y=564, which is fine in a toolbar row but reads as
     * small and low when it is the only thing on its line. Here it spans 216..744 by 310..650, so
     * its centre is (480, 480) like every other icon in the set and it carries a line of its own.
     */
    val expandLess by icon(GeneratedIconPaths.expandLess)

    /**
     * The overflow menu's three dots.
     *
     * Three circles rather than three squares, drawn as arc pairs. Deliberately narrow - a vertical
     * ellipsis is 160 units wide against 676 tall, and widening it to satisfy a "fills the viewport"
     * rule would make it something other than the glyph everyone recognises.
     */
    val moreVert by icon(GeneratedIconPaths.moreVert)

    /** An arrow into a tray: download, kept beside the like button where songs are acted on. */
    val download by icon(GeneratedIconPaths.download)

    /**
     * Two stacked bars: the grip for dragging a queue row.
     *
     * Wide and short, which is the shape that reads as "grab here and move vertically" - the same
     * convention every reorderable list uses, and the reason it needs no label.
     */
    val dragHandle by icon(GeneratedIconPaths.dragHandle)

    /** A crescent: the sleep timer, which is what it means on every phone ever made. */
    val bedtime by icon(GeneratedIconPaths.bedtime)

    // ---- Settings rows -----------------------------------------------------------------------
    //
    // Pulled from Google's published Material Symbols Outlined set (matching the family every icon
    // above already is - verified by diffing several against the app's own res/drawable vectors,
    // which are the same glyphs at the same 960 viewport) rather than Rounded, so a settings screen
    // with both families side by side does not read as two different icon sets. Android's own
    // `Icons.Rounded.*` names are kept as the doc reference for each because that is what a reader
    // comparing against the phone's settings code will be looking for, even though the drawn shape
    // here is Outlined.

    /** Android: `Icons.Rounded.AccountCircle`. */
    val accountCircle by icon(GeneratedIconPaths.accountCircle)

    /** Android: `Icons.Rounded.Person`. */
    val person by icon(GeneratedIconPaths.person)

    /** Android: `Icons.AutoMirrored.Rounded.Logout`. */
    val logout by icon(GeneratedIconPaths.logout)

    /** Android: `Icons.Rounded.Key`. */
    val key by icon(GeneratedIconPaths.key)

    /** Android: `Icons.Rounded.NetworkCheck`. */
    val networkCheck by icon(GeneratedIconPaths.networkCheck)

    /** Android: `Icons.Rounded.GraphicEq`. */
    val graphicEq by icon(GeneratedIconPaths.graphicEq)

    /** Android: `Icons.Rounded.DarkMode`. */
    val darkMode by icon(GeneratedIconPaths.darkMode)

    /** Android: `Icons.Rounded.Palette`. */
    val palette by icon(GeneratedIconPaths.palette)
    val keyboard by icon(GeneratedIconPaths.keyboard)

    /** Android: `Icons.Rounded.BlurOn`. */
    val blurOn by icon(GeneratedIconPaths.blurOn)

    /** Android: `Icons.Rounded.Delete`. */
    val delete by icon(GeneratedIconPaths.delete)

    /** Android: `Icons.Rounded.History`. */
    val history by icon(GeneratedIconPaths.history)

    /** Android: `Icons.Rounded.Restore` (Material Symbols has no direct equivalent; this is
     * `settings_backup_restore`, distinguishable from [history] by the centre dot on the clock). */
    val restore by icon(GeneratedIconPaths.restore)

    /** Android: `Icons.Rounded.Timer`. */
    val timer by icon(GeneratedIconPaths.timer)

    /** Android: `Icons.Rounded.Cast`. */
    val cast by icon(GeneratedIconPaths.cast)

    /** Android: `Icons.Rounded.CastConnected`. */
    val castConnected by icon(GeneratedIconPaths.castConnected)

    /** Android: `Icons.Rounded.Stop`. */
    val stop by icon(GeneratedIconPaths.stop)

    /** Android: `Icons.Rounded.Devices`. */
    val devices by icon(GeneratedIconPaths.devices)

    /** Android: `Icons.Rounded.MusicNote`, the follower's now-playing row. Distinct from the
     * transport [play]/[pause] glyphs, and from `app/src/main/res/drawable/music_note.xml`'s own
     * copy of the same shape. */
    val musicNote by icon(GeneratedIconPaths.musicNote)

    // The navigation rail's four. Search is the app's own drawable (shortcut_search);
    // home and settings are drawn on the same grid to match.
    val home by icon(GeneratedIconPaths.home)

    val search by icon(GeneratedIconPaths.search)

    // For the player-button arrangement, which can put any menu action beside the title. Drawn on
    // the same 960 grid as the rest.
    val share by icon(GeneratedIconPaths.share)

    val album by icon(GeneratedIconPaths.album)

    val playlistAdd by icon(GeneratedIconPaths.playlistAdd)

    val settings by icon(GeneratedIconPaths.settings)

    /** Android: `Icons.Rounded.Info`. */
    val info by icon(GeneratedIconPaths.info)

    /** Android: `Icons.Rounded.Wifi`. */
    val wifi by icon(GeneratedIconPaths.wifi)

    /**
     * Android: `Icons.Rounded.ColorLens`, next to "colour controls by value" - a desktop-only row
     * with no exact Material Symbols match (Symbols folded `color_lens` into `palette`, which this
     * screen already uses for a different row). Drawn from the legacy Material Icons Round glyph
     * instead, at a 24x24 viewport scaled ×40 to this file's convention, rather than reusing
     * [palette] for two rows that mean different things.
     */
    val colorLens by icon(GeneratedIconPaths.colorLens)

    /** Android: `Icons.Rounded.Lyrics`, next to "tap artwork for lyrics" on the phone. */
    val lyrics by icon(GeneratedIconPaths.lyrics)

    /** Android: `Icons.Rounded.TextRotationAngledown`, next to the karaoke word-sweep toggle. */
    val textRotationAngledown by icon(GeneratedIconPaths.textRotationAngledown)

    /**
     * Discord's own mark, for the rich-presence section - not a Material Symbol, and not on the
     * 960 grid the rest of this file uses. Path lifted verbatim from
     * `app/src/main/res/drawable/discord.xml`, which publishes it at a 127.14 x 96.36 viewport;
     * reproduced at that same viewport here rather than rescaled, since rescaling by hand is exactly
     * the kind of arithmetic that silently distorts a logo.
     */
    val discord: ImageVector by lazy {
        val pathData = "M107.7,8.07A105.15,105.15 0,0 0,81.47 0a72.06,72.06 0,0 0,-3.36 6.83A97.68,97.68 0,0 0,49 6.83," +
            "72.37 72.37,0 0,0 45.64,0 105.89,105.89 0,0 0,19.39 8.09C2.79,32.65 -1.71,56.6 0.54,80.21h0A105.73,105.73 0,0 0," +
            "32.71 96.36,77.7 77.7,0 0,0 39.6,85.25a68.42,68.42 0,0 1,-10.85 -5.18c0.91,-0.66 1.8,-1.34 2.66,-2a75.57,75.57 0,0 0," +
            "64.32 0c0.87,0.71 1.76,1.39 2.66,2a68.68,68.68 0,0 1,-10.87 5.19,77 77,0 0,0 6.89,11.1A105.25,105.25 0,0 0," +
            "126.6 80.22h0C129.24,52.84 122.09,29.11 107.7,8.07ZM42.45,65.69C36.18,65.69 31,60 31,53s5,-12.74 11.43,-12.74S54," +
            "46 53.89,53 48.84,65.69 42.45,65.69ZM84.69,65.69C78.41,65.69 73.25,60 73.25,53s5,-12.74 11.44,-12.74S96.23,46 96.12,53" +
            " 91.08,65.69 84.69,65.69Z"
        // Not registered into `paths` - see the comment on `all` for why this icon is excluded
        // from the geometry tests that map runs against.
        ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 18.dp,
            viewportWidth = 127.14f,
            viewportHeight = 96.36f,
        ).apply {
            addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.White))
        }.build()
    }

    /** Every icon by name, so a test can check them all rather than the ones somebody remembered. */
    internal val all: Map<String, ImageVector>
        get() = mapOf(
            "play" to play,
            "pause" to pause,
            "skipNext" to skipNext,
            "skipPrevious" to skipPrevious,
            "shuffle" to shuffle,
            "repeat" to repeat,
            "repeatOne" to repeatOne,
            "favorite" to favorite,
            "favoriteBorder" to favoriteBorder,
            "close" to close,
            "queueMusic" to queueMusic,
            "expandLess" to expandLess,
            "moreVert" to moreVert,
            "download" to download,
            "dragHandle" to dragHandle,
            "bedtime" to bedtime,
            "fastForward" to fastForward,
            "fastRewind" to fastRewind,
            "equalizer" to equalizer,
            "accountCircle" to accountCircle,
            "person" to person,
            "logout" to logout,
            "key" to key,
            "networkCheck" to networkCheck,
            "graphicEq" to graphicEq,
            "darkMode" to darkMode,
            "palette" to palette,
            "keyboard" to keyboard,
            "blurOn" to blurOn,
            "delete" to delete,
            "history" to history,
            "restore" to restore,
            "timer" to timer,
            "cast" to cast,
            "castConnected" to castConnected,
            "stop" to stop,
            "devices" to devices,
            "musicNote" to musicNote,
            "home" to home,
            "search" to search,
            "settings" to settings,
            "share" to share,
            "album" to album,
            "playlistAdd" to playlistAdd,
            "info" to info,
            "wifi" to wifi,
            "textRotationAngledown" to textRotationAngledown,
            "lyrics" to lyrics,
            "colorLens" to colorLens,
            // discord deliberately excluded: it is a third-party brand mark on its own small
            // viewport (127.14 x 96.36), not this file's shared 960 grid, so it has no place in
            // geometry tests written to check that every icon here fills the same canvas
            // consistently - those assertions would just be measuring a different coordinate space.
        )

    /** The path strings, so a test can measure the geometry rather than trust the rendering. */
    internal val allPaths: Map<String, String>
        get() = paths.toMap()

    /**
     * Built once and reused. An ImageVector is immutable and parsing the path is not free, so a
     * property that rebuilt on every read would re-parse on every recomposition of every row.
     */
    /**
     * Declares an icon, and registers its path under the name of the property holding it.
     *
     * A delegate *provider* rather than a plain lazy, so the property name is available at the
     * moment the delegate is created. That is what keeps [paths] honest: there is no second list of
     * names to fall out of step, and an icon added tomorrow is covered by the geometry test without
     * anyone remembering to add it.
     *
     * Registration happens at construction; the ImageVector itself is still built lazily, because
     * parsing is not free and a property that re-parsed on every read would do so on every
     * recomposition of every row.
     */
    private fun icon(pathData: String) =
        PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, ImageVector>> { _, property ->
            paths[property.name] = pathData
            val vector = lazy {
                ImageVector.Builder(
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 960f,
                    viewportHeight = 960f,
                ).apply {
                    // White, so that Icon's own tint is what actually colours it - a tint
                    // multiplies, and a path filled with anything darker would come out muddy
                    // wherever it was tinted.
                    //
                    // Exactly as the phone draws them: filled, no stroke. The phone's icons are
                    // Material's Rounded set, which already has the soft corners.
                    addPath(
                        PathParser().parsePathString(pathData).toNodes(),
                        fill = SolidColor(Color.White),
                    )
                }.build()
            }
            ReadOnlyProperty { _, _ -> vector.value }
        }
}
