/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

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

    val play by icon("M320,760L320,200L760,480L320,760Z")

    val pause by icon("M560,760L560,200L720,200L720,760L560,760ZM240,760L240,200L400,200L400,760L240,760Z")

    val skipNext by icon("M660,720L660,240L740,240L740,720L660,720ZM220,720L220,240L580,480L220,720Z")

    val skipPrevious by icon("M220,720L220,240L300,240L300,720L220,720ZM740,720L380,480L740,240L740,720Z")

    val shuffle by icon(
        "M560,800L560,720L664,720L536,592L593,535L720,662L720,560L800,560L800,800L560,800Z" +
            "M216,800L160,744L664,240L560,240L560,160L800,160L800,400L720,400L720,296L216,800Z" +
            "M367,423L160,216L216,160L423,367L367,423Z"
    )

    val repeat by icon(
        "M280,880L120,720L280,560L336,618L274,680L680,680L680,520L760,520L760,760L274,760L336,822L280,880Z" +
            "M200,440L200,200L686,200L624,138L680,80L840,240L680,400L624,342L686,280L280,280L280,440L200,440Z"
    )

    val repeatOne by icon(
        "M460,600v-180h-60v-60h120v240h-60ZM280,880 L120,720l160,-160 56,58 -62,62h406v-160h80v240L274,760l62,62 -56,58Z" +
            "M200,440v-240h486l-62,-62 56,-58 160,160 -160,160 -56,-58 62,-62L280,280v160h-80Z"
    )

    val favorite by icon(
        "M480,840L422,788Q321,697 255,631Q189,565 150,512.5Q111,460 95.5,416Q80,372 80,326Q80,232 143,169" +
            "Q206,106 300,106Q352,106 399,128Q446,150 480,190Q514,150 561,128Q608,106 660,106Q754,106 817,169" +
            "Q880,232 880,326Q880,372 864.5,416Q849,460 810,512.5Q771,565 705,631Q639,697 538,788L480,840Z"
    )

    val favoriteBorder by icon(
        "M480,840L422,788Q321,697 255,631Q189,565 150,512.5Q111,460 95.5,416Q80,372 80,326Q80,232 143,169" +
            "Q206,106 300,106Q352,106 399,128Q446,150 480,190Q514,150 561,128Q608,106 660,106Q754,106 817,169" +
            "Q880,232 880,326Q880,372 864.5,416Q849,460 810,512.5Q771,565 705,631Q639,697 538,788L480,840Z" +
            "M480,732Q576,646 638,584.5Q700,523 736,477.5Q772,432 786,396.5Q800,361 800,326Q800,266 760,226" +
            "Q720,186 660,186Q613,186 573,212.5Q533,239 518,280L442,280Q427,239 387,212.5Q347,186 300,186" +
            "Q240,186 200,226Q160,266 160,326Q160,361 174,396.5Q188,432 224,477.5Q260,523 322,584.5Q384,646 480,732Z"
    )

    val close by icon("M256,760L200,704L424,480L200,256L256,200L480,424L704,200L760,256L536,480L760,704L704,760L480,536L256,760Z")

    val queueMusic by icon(
        "M640,800Q590,800 555,765Q520,730 520,680Q520,630 555,595Q590,560 640,560Q651,560 661,561.5Q671,563 680,568" +
            "L680,240L880,240L880,320L760,320L760,680Q760,730 725,765Q690,800 640,800Z" +
            "M120,640L120,560L440,560L440,640L120,640ZM120,480L120,400L600,400L600,480L120,480Z" +
            "M120,320L120,240L600,240L600,320L120,320Z"
    )

    /**
     * Two triangles, no bar - the same glyphs Android reaches for with `Icons.Rounded.FastRewind`
     * and `FastForward` on the seek buttons either side of play.
     *
     * Drawn on the 960 grid rather than copied from a drawable, because the app has no drawable for
     * these - it uses the Material set. Each triangle is the one already in [skipNext], so the pair
     * match the rest of the transport row in weight, and the geometry is checked by test: the group
     * spans 100..860 horizontally and 230..730 vertically, both centred on 480.
     */
    val fastForward by icon(
        "M100,730L100,230L460,480L100,730Z" +
            "M500,730L500,230L860,480L500,730Z"
    )

    val fastRewind by icon(
        "M860,730L860,230L500,480L860,730Z" +
            "M460,730L460,230L100,480L460,730Z"
    )

    /**
     * Three bars of different heights, standing on a common baseline.
     *
     * Bottom-aligned rather than centred individually, because an equaliser's bars rise from a floor
     * - centring each one would read as a bar chart of nothing in particular. The group spans
     * 160..800 in both directions, so it sits centred in the viewport like every other icon here.
     */
    val equalizer by icon(
        "M160,800L160,520L280,520L280,800L160,800Z" +
            "M420,800L420,160L540,160L540,800L420,800Z" +
            "M680,800L680,400L800,400L800,800L680,800Z"
    )

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
    val expandLess by icon("M480,439L278,650L216,586L480,310L744,586L682,650L480,439Z")

    /**
     * The overflow menu's three dots.
     *
     * Three circles rather than three squares, drawn as arc pairs. Deliberately narrow - a vertical
     * ellipsis is 160 units wide against 676 tall, and widening it to satisfy a "fills the viewport"
     * rule would make it something other than the glyph everyone recognises.
     */
    val moreVert by icon(
        "M402,220 A78,78 0 1,0 558,220 A78,78 0 1,0 402,220 Z" +
            "M402,480 A78,78 0 1,0 558,480 A78,78 0 1,0 402,480 Z" +
            "M402,740 A78,78 0 1,0 558,740 A78,78 0 1,0 402,740 Z"
    )

    /** An arrow into a tray: download, kept beside the like button where songs are acted on. */
    val download by icon(
        "M440,160 L520,160 L520,486 L624,382 L680,440 L480,640 L280,440 L336,382 L440,486 Z" +
            "M240,720 L720,720 L720,800 L240,800 Z"
    )

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
            "fastForward" to fastForward,
            "fastRewind" to fastRewind,
            "equalizer" to equalizer,
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
                    addPath(
                        PathParser().parsePathString(pathData).toNodes(),
                        fill = SolidColor(Color.White),
                    )
                }.build()
            }
            ReadOnlyProperty { _, _ -> vector.value }
        }
}
