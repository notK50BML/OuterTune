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

    /**
     * Two stacked bars: the grip for dragging a queue row.
     *
     * Wide and short, which is the shape that reads as "grab here and move vertically" - the same
     * convention every reorderable list uses, and the reason it needs no label.
     */
    val dragHandle by icon(
        "M160,360 L800,360 L800,440 L160,440 Z" +
            "M160,520 L800,520 L800,600 L160,600 Z"
    )

    /** A crescent: the sleep timer, which is what it means on every phone ever made. */
    val bedtime by icon(
        "M480,800q-133,0-226.5,-93.5T160,480q0,-133,93.5,-226.5T480,160q12,0,25,1t26,3" +
            "q-57,40,-90.5,104T407,407q0,90,64,154t154,64q69,0,125,-33t105,-90q2,13,3,26t1,25" +
            "q0,133,-93.5,226.5T480,800Z"
    )

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
    val accountCircle by icon(
        "M234,684q51,-39,114,-61.5T480,600q69,0,132,22.5T726,684q35,-41,54.5,-93T800,480q0,-133,-93.5,-226.5T480,160" +
            "q-133,0,-226.5,93.5T160,480q0,59,19.5,111t54.5,93Zm246,-164q-59,0,-99.5,-40.5T340,380q0,-59,40.5,-99.5T480,240" +
            "q59,0,99.5,40.5T620,380q0,59,-40.5,99.5T480,520Zm0,360q-83,0,-156,-31.5T197,763q-54,-54,-85.5,-127T80,480" +
            "q0,-83,31.5,-156T197,197q54,-54,127,-85.5T480,80q83,0,156,31.5T763,197q54,54,85.5,127T880,480q0,83,-31.5,156" +
            "T763,763q-54,54,-127,85.5T480,880Zm0,-80q53,0,100,-15.5t86,-44.5q-39,-29,-86,-44.5T480,680q-53,0,-100,15.5" +
            "T294,740q39,29,86,44.5T480,800Zm0,-360q26,0,43,-17t17,-43q0,-26,-17,-43t-43,-17q-26,0,-43,17t-17,43" +
            "q0,26,17,43t43,17Zm0,-60Zm0,360Z"
    )

    /** Android: `Icons.Rounded.Person`. */
    val person by icon(
        "M480,480q-66,0,-113,-47t-47,-113q0,-66,47,-113t113,-47q66,0,113,47t47,113q0,66,-47,113t-113,47ZM160,800v-112" +
            "q0,-34,17.5,-62.5T224,582q62,-31,126,-46.5T480,520q66,0,130,15.5T736,582q29,15,46.5,43.5T800,688v112H160Zm80,-80h480v-32" +
            "q0,-11,-5.5,-20T700,654q-54,-27,-109,-40.5T480,600q-56,0,-111,13.5T260,654q-9,5,-14.5,14t-5.5,20v32Zm240,-320" +
            "q33,0,56.5,-23.5T560,320q0,-33,-23.5,-56.5T480,240q-33,0,-56.5,23.5T400,320q0,33,23.5,56.5T480,400Zm0,-80Zm0,400Z"
    )

    /** Android: `Icons.AutoMirrored.Rounded.Logout`. */
    val logout by icon(
        "M200,840q-33,0,-56.5,-23.5T120,760v-560q0,-33,23.5,-56.5T200,120h280v80H200v560h280v80H200Zm440,-160l-55,-58" +
            "l102,-102H360v-80h327L585,338l55,-58l200,200l-200,200Z"
    )

    /** Android: `Icons.Rounded.Key`. */
    val key by icon(
        "M280,560q-33,0,-56.5,-23.5T200,480q0,-33,23.5,-56.5T280,400q33,0,56.5,23.5T360,480q0,33,-23.5,56.5T280,560Zm0,160" +
            "q-100,0,-170,-70T40,480q0,-100,70,-170t170,-70q67,0,121.5,33t86.5,87h352l120,120l-180,180l-80,-60l-80,60l-85,-60h-47" +
            "q-32,54,-86.5,87T280,720Zm0,-80q56,0,98.5,-34t56.5,-86h125l58,41l82,-61l71,55l75,-75l-40,-40H435" +
            "q-14,-52,-56.5,-86T280,320q-66,0,-113,47t-47,113q0,66,47,113t113,47Z"
    )

    /** Android: `Icons.Rounded.NetworkCheck`. */
    val networkCheck by icon(
        "M84,444L0,360q95,-97,219.5,-148.5T480,160q24,0,48,1.5t48,4.5l-60,116q-9,-1,-18,-1.5t-18,-0.5" +
            "q-112,0,-214.5,42.5T84,444Zm170,170l-84,-86q57,-57,131,-89t155,-37l-64,131q-39,11,-74,31.5T254,614Zm198,180" +
            "q-33,-11,-48,-41.5t0,-60.5l240,-488q4,-8,12,-10.5t16,0.5q8,3,12,10.5t2,15.5L556,746q-8,33,-39.5,47t-64.5,1Z" +
            "m254,-180q-7,-7,-13.5,-12.5T678,590l32,-125q21,14,41.5,29.5T790,528l-84,86Zm169,-169q-32,-29,-65.5,-55T738,344" +
            "l28,-120q54,26,103,60t91,76l-85,85Z"
    )

    /** Android: `Icons.Rounded.GraphicEq`. */
    val graphicEq by icon(
        "M280,720v-480h80v480h-80ZM440,880v-800h80v800h-80ZM120,560v-160h80v160h-80Zm480,160v-480h80v480h-80Zm160,-160" +
            "v-160h80v160h-80Z"
    )

    /** Android: `Icons.Rounded.DarkMode`. */
    val darkMode by icon(
        "M480,840q-150,0,-255,-105T120,480q0,-150,105,-255t255,-105q14,0,27.5,1t26.5,3q-41,29,-65.5,75.5T444,300" +
            "q0,90,63,153t153,63q55,0,101,-24.5t75,-65.5q2,13,3,26.5t1,27.5q0,150,-105,255T480,840Zm0,-80q88,0,158,-48.5T740,585" +
            "q-20,5,-40,8t-40,3q-123,0,-209.5,-86.5T364,300q0,-20,3,-40t8,-40q-78,32,-126.5,102T200,480q0,116,82,198t198,82Z" +
            "m-10,-270Z"
    )

    /** Android: `Icons.Rounded.Palette`. */
    val palette by icon(
        "M480,880q-82,0,-155,-31.5t-127.5,-86Q143,708,111.5,635T80,480q0,-83,32.5,-156t88,-127Q256,143,330,111.5T488,80" +
            "q80,0,151,27.5t124.5,76q53.5,48.5,85,115T880,442q0,115,-70,176.5T640,680h-74q-9,0,-12.5,5t-3.5,11q0,12,15,34.5t15,51.5" +
            "q0,50,-27.5,74T480,880Zm0,-400Zm-220,40q26,0,43,-17t17,-43q0,-26,-17,-43t-43,-17q-26,0,-43,17t-17,43q0,26,17,43" +
            "t43,17Zm120,-160q26,0,43,-17t17,-43q0,-26,-17,-43t-43,-17q-26,0,-43,17t-17,43q0,26,17,43t43,17Zm200,0q26,0,43,-17" +
            "t17,-43q0,-26,-17,-43t-43,-17q-26,0,-43,17t-17,43q0,26,17,43t43,17Zm120,160q26,0,43,-17t17,-43q0,-26,-17,-43t-43,-17" +
            "q-26,0,-43,17t-17,43q0,26,17,43t43,17ZM480,800q9,0,14.5,-5t5.5,-13q0,-14,-15,-33t-15,-57q0,-42,29,-67t71,-25h70" +
            "q66,0,113,-38.5T800,442q0,-121,-92.5,-201.5T488,160q-136,0,-232,93t-96,227q0,133,93.5,226.5T480,800Z"
    )

    /** Android: `Icons.Rounded.BlurOn`. */
    val blurOn by icon(
        "M120,580q-8,0,-14,-6t-6,-14q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Zm0,-160q-8,0,-14,-6t-6,-14q0,-8,6,-14" +
            "t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Zm120,340q-17,0,-28.5,-11.5T200,720q0,-17,11.5,-28.5T240,680q17,0,28.5,11.5" +
            "T280,720q0,17,-11.5,28.5T240,760Zm0,-160q-17,0,-28.5,-11.5T200,560q0,-17,11.5,-28.5T240,520q17,0,28.5,11.5" +
            "T280,560q0,17,-11.5,28.5T240,600Zm0,-160q-17,0,-28.5,-11.5T200,400q0,-17,11.5,-28.5T240,360q17,0,28.5,11.5" +
            "T280,400q0,17,-11.5,28.5T240,440Zm0,-160q-17,0,-28.5,-11.5T200,240q0,-17,11.5,-28.5T240,200q17,0,28.5,11.5" +
            "T280,240q0,17,-11.5,28.5T240,280Zm160,340q-25,0,-42.5,-17.5T340,560q0,-25,17.5,-42.5T400,500q25,0,42.5,17.5" +
            "T460,560q0,25,-17.5,42.5T400,620Zm0,-160q-25,0,-42.5,-17.5T340,400q0,-25,17.5,-42.5T400,340q25,0,42.5,17.5" +
            "T460,400q0,25,-17.5,42.5T400,460Zm0,300q-17,0,-28.5,-11.5T360,720q0,-17,11.5,-28.5T400,680q17,0,28.5,11.5" +
            "T440,720q0,17,-11.5,28.5T400,760Zm0,-480q-17,0,-28.5,-11.5T360,240q0,-17,11.5,-28.5T400,200q17,0,28.5,11.5" +
            "T440,240q0,17,-11.5,28.5T400,280Zm0,580q-8,0,-14,-6t-6,-14q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Z" +
            "m0,-720q-8,0,-14,-6t-6,-14q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Zm160,480q-25,0,-42.5,-17.5T500,560" +
            "q0,-25,17.5,-42.5T560,500q25,0,42.5,17.5T620,560q0,25,-17.5,42.5T560,620Zm0,-160q-25,0,-42.5,-17.5T500,400" +
            "q0,-25,17.5,-42.5T560,340q25,0,42.5,17.5T620,400q0,25,-17.5,42.5T560,460Zm0,300q-17,0,-28.5,-11.5T520,720" +
            "q0,-17,11.5,-28.5T560,680q17,0,28.5,11.5T600,720q0,17,-11.5,28.5T560,760Zm0,-480q-17,0,-28.5,-11.5T520,240" +
            "q0,-17,11.5,-28.5T560,200q17,0,28.5,11.5T600,240q0,17,-11.5,28.5T560,280Zm0,580q-8,0,-14,-6t-6,-14q0,-8,6,-14" +
            "t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Zm0,-720q-8,0,-14,-6t-6,-14q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Z" +
            "m160,620q-17,0,-28.5,-11.5T680,720q0,-17,11.5,-28.5T720,680q17,0,28.5,11.5T760,720q0,17,-11.5,28.5T720,760Z" +
            "m0,-160q-17,0,-28.5,-11.5T680,560q0,-17,11.5,-28.5T720,520q17,0,28.5,11.5T760,560q0,17,-11.5,28.5T720,600Z" +
            "m0,-160q-17,0,-28.5,-11.5T680,400q0,-17,11.5,-28.5T720,360q17,0,28.5,11.5T760,400q0,17,-11.5,28.5T720,440Z" +
            "m0,-160q-17,0,-28.5,-11.5T680,240q0,-17,11.5,-28.5T720,200q17,0,28.5,11.5T760,240q0,17,-11.5,28.5T720,280Z" +
            "m120,300q-8,0,-14,-6t-6,-14q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Zm0,-160q-8,0,-14,-6t-6,-14" +
            "q0,-8,6,-14t14,-6q8,0,14,6t6,14q0,8,-6,14t-14,6Z"
    )

    /** Android: `Icons.Rounded.Delete`. */
    val delete by icon(
        "M280,840q-33,0,-56.5,-23.5T200,760v-520h-40v-80h200v-40h240v40h200v80h-40v520q0,33,-23.5,56.5T680,840H280Z" +
            "m400,-600H280v520h400v-520ZM360,680h80v-360h-80v360Zm160,0h80v-360h-80v360ZM280,240v520v-520Z"
    )

    /** Android: `Icons.Rounded.History`. */
    val history by icon(
        "M480,840q-138,0,-240.5,-91.5T122,520h82q14,104,92.5,172T480,760q117,0,198.5,-81.5T760,480q0,-117,-81.5,-198.5" +
            "T480,200q-69,0,-129,32t-101,88h110v80H120v-240h80v94q51,-64,124.5,-99T480,120q75,0,140.5,28.5t114,77" +
            "q48.5,48.5,77,114T840,480q0,75,-28.5,140.5t-77,114q-48.5,48.5,-114,77T480,840Zm112,-192L440,496v-216h80v184" +
            "l128,128l-56,56Z"
    )

    /** Android: `Icons.Rounded.Restore` (Material Symbols has no direct equivalent; this is
     * `settings_backup_restore`, distinguishable from [history] by the centre dot on the clock). */
    val restore by icon(
        "M480,560q-33,0,-56.5,-23.5T400,480q0,-33,23.5,-56.5T480,400q33,0,56.5,23.5T560,480q0,33,-23.5,56.5T480,560Z" +
            "m0,280q-139,0,-241,-91.5T122,520h82q14,104,92.5,172T480,760q117,0,198.5,-81.5T760,480q0,-117,-81.5,-198.5" +
            "T480,200q-69,0,-129,32t-101,88h110v80H120v-240h80v94q51,-64,124.5,-99T480,120q75,0,140.5,28.5t114,77" +
            "q48.5,48.5,77,114T840,480q0,75,-28.5,140.5t-77,114q-48.5,48.5,-114,77T480,840Z"
    )

    /** Android: `Icons.Rounded.Timer`. */
    val timer by icon(
        "M360,120v-80h240v80H360Zm80,440h80v-240h-80v240Zm40,320q-74,0,-139.5,-28.5T226,774q-49,-49,-77.5,-114.5" +
            "T120,520q0,-74,28.5,-139.5T226,266q49,-49,114.5,-77.5T480,160q62,0,119,20t107,58l56,-56l56,56l-56,56" +
            "q38,50,58,107t20,119q0,74,-28.5,139.5T734,774q-49,49,-114.5,77.5T480,880Zm0,-80q116,0,198,-82t82,-198" +
            "q0,-116,-82,-198t-198,-82q-116,0,-198,82t-82,198q0,116,82,198t198,82Zm0,-280Z"
    )

    /** Android: `Icons.Rounded.Cast`. */
    val cast by icon(
        "M480,480Zm320,320H600q0,-20,-1.5,-40t-4.5,-40h206v-480H160v46q-20,-3,-40,-4.5T80,280v-40q0,-33,23.5,-56.5" +
            "T160,160h640q33,0,56.5,23.5T880,240v480q0,33,-23.5,56.5T800,800Zm-720,0v-120q50,0,85,35t35,85H80Zm200,0" +
            "q0,-83,-58.5,-141.5T80,600v-80q117,0,198.5,81.5T360,800h-80Zm160,0q0,-75,-28.5,-140.5t-77,-114" +
            "q-48.5,-48.5,-114,-77T80,440v-80q91,0,171,34.5T391,489q60,60,94.5,140T520,800h-80Z"
    )

    /** Android: `Icons.Rounded.CastConnected`. */
    val castConnected by icon(
        "M720,640H575q-7,-21,-15.5,-41.5T542,560h98v-160H413q-29,-25,-62.5,-45T281,320h439v320ZM480,480ZM80,800v-120" +
            "q50,0,85,35t35,85H80Zm200,0q0,-83,-58.5,-141.5T80,600v-80q117,0,198.5,81.5T360,800h-80Zm160,0" +
            "q0,-75,-28.5,-140.5t-77,-114q-48.5,-48.5,-114,-77T80,440v-80q91,0,171,34.5T391,489q60,60,94.5,140T520,800h-80Z" +
            "m360,0H600q0,-20,-1.5,-40t-4.5,-40h206v-480H160v46q-20,-3,-40,-4.5T80,280v-40q0,-33,23.5,-56.5T160,160h640" +
            "q33,0,56.5,23.5T880,240v480q0,33,-23.5,56.5T800,800Z"
    )

    /** Android: `Icons.Rounded.Stop`. */
    val stop by icon(
        "M320,320v320v-320Zm-80,400v-480h480v480H240Zm80,-80h320v-320H320v320Z"
    )

    /** Android: `Icons.Rounded.Devices`. */
    val devices by icon(
        "M80,800v-120h80v-440q0,-33,23.5,-56.5T240,160h600v80H240v440h240v120H80Zm520,0q-17,0,-28.5,-11.5T560,760v-400" +
            "q0,-17,11.5,-28.5T600,320h240q17,0,28.5,11.5T880,360v400q0,17,-11.5,28.5T840,800H600Zm40,-120h160v-280H640v280Z" +
            "m0,0h160h-160Z"
    )

    /** Android: `Icons.Rounded.MusicNote`, the follower's now-playing row. Distinct from the
     * transport [play]/[pause] glyphs, and from `app/src/main/res/drawable/music_note.xml`'s own
     * copy of the same shape. */
    val musicNote by icon(
        "M400,840q-66,0,-113,-47t-47,-113q0,-66,47,-113t113,-47q23,0,42.5,5.5T480,542v-422h240v160H560v400q0,66,-47,113" +
            "t-113,47Z"
    )

    /** Android: `Icons.Rounded.Info`. */
    val info by icon(
        "M440,680h80v-240h-80v240Zm40,-320q17,0,28.5,-11.5T520,320q0,-17,-11.5,-28.5T480,280q-17,0,-28.5,11.5" +
            "T440,320q0,17,11.5,28.5T480,360Zm0,520q-83,0,-156,-31.5T197,763q-54,-54,-85.5,-127T80,480q0,-83,31.5,-156" +
            "T197,197q54,-54,127,-85.5T480,80q83,0,156,31.5T763,197q54,54,85.5,127T880,480q0,83,-31.5,156T763,763" +
            "q-54,54,-127,85.5T480,880Zm0,-80q134,0,227,-93t93,-227q0,-134,-93,-227t-227,-93q-134,0,-227,93t-93,227" +
            "q0,134,93,227t227,93Zm0,-320Z"
    )

    /** Android: `Icons.Rounded.Wifi`. */
    val wifi by icon(
        "M480,840q-42,0,-71,-29t-29,-71q0,-42,29,-71t71,-29q42,0,71,29t29,71q0,42,-29,71t-71,29ZM254,614l-84,-86" +
            "q59,-59,138.5,-93.5T480,400q92,0,171.5,35T790,530l-84,84q-44,-44,-102,-69t-124,-25q-66,0,-124,25t-102,69Z" +
            "M84,444L0,360q92,-94,215,-147t265,-53q142,0,265,53t215,147l-84,84q-77,-77,-178.5,-120.5T480,280" +
            "q-116,0,-217.5,43.5T84,444Z"
    )

    /**
     * Android: `Icons.Rounded.ColorLens`, next to "colour controls by value" - a desktop-only row
     * with no exact Material Symbols match (Symbols folded `color_lens` into `palette`, which this
     * screen already uses for a different row). Drawn from the legacy Material Icons Round glyph
     * instead, at a 24x24 viewport scaled ×40 to this file's convention, rather than reusing
     * [palette] for two rows that mean different things.
     */
    val colorLens by icon(
        "M480,120c-198.8,0,-360,161.2,-360,360s161.2,360,360,360c33.2,0,60,-26.8,60,-60c0,-15.6,-6,-29.6,-15.6,-40.4" +
            "c-9.2,-10.4,-15.2,-24.4,-15.2,-39.6c0,-33.2,26.8,-60,60,-60H640c110.4,0,200,-89.6,200,-200" +
            "c0,-176.8,-161.2,-320,-360,-320zm-220,360c-33.2,0,-60,-26.8,-60,-60S226.8,360,260,360S320,386.8,320,420" +
            "S293.2,480,260,480zm120,-160C346.8,320,320,293.2,320,260S346.8,200,380,200s60,26.8,60,60S413.2,320,380,320z" +
            "m200,0c-33.2,0,-60,-26.8,-60,-60S546.8,200,580,200s60,26.8,60,60S613.2,320,580,320zm120,160" +
            "c-33.2,0,-60,-26.8,-60,-60S666.8,360,700,360s60,26.8,60,60s-26.8,60,-60,60z"
    )

    /** Android: `Icons.Rounded.Lyrics`, next to "tap artwork for lyrics" on the phone. */
    val lyrics by icon(
        "M160,640v-480v480Zm440,80H240l-92,92q-19,19,-43.5,8.5T80,783v-623q0,-33,23.5,-56.5T160,80h440q33,0,56.5,23.5" +
            "T680,160v40q0,17,-11.5,28.5T640,240q-17,0,-28.5,-11.5T600,200v-40H160v480h440v-120q0,-17,11.5,-28.5T640,480" +
            "q17,0,28.5,11.5T680,520v120q0,33,-23.5,56.5T600,720ZM280,560h80q17,0,28.5,-11.5T400,520q0,-17,-11.5,-28.5" +
            "T360,480h-80q-17,0,-28.5,11.5T240,520q0,17,11.5,28.5T280,560Zm480,-80q-50,0,-85,-35t-35,-85q0,-50,35,-85" +
            "t85,-35q11,0,21,2t19,5v-167q0,-17,11.5,-28.5T840,40h80q17,0,28.5,11.5T960,80q0,17,-11.5,28.5T920,120h-40v240" +
            "q0,50,-35,85t-85,35ZM280,440h200q17,0,28.5,-11.5T520,400q0,-17,-11.5,-28.5T480,360H280q-17,0,-28.5,11.5" +
            "T240,400q0,17,11.5,28.5T280,440Zm0,-120h200q17,0,28.5,-11.5T520,280q0,-17,-11.5,-28.5T480,240H280" +
            "q-17,0,-28.5,11.5T240,280q0,17,11.5,28.5T280,320Z"
    )

    /** Android: `Icons.Rounded.TextRotationAngledown`, next to the karaoke word-sweep toggle. */
    val textRotationAngledown by icon(
        "M400,840v-80h64L92,388l56,-56l372,372v-64h80v200H400Zm204,-222l-54,-54l52,-106l-126,-126l-106,50l-54,-54" +
            "l428,-194l56,56l-196,428Zm-66,-316l92,94l84,-174l-2,-2l-174,82Z"
    )

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
                    addPath(
                        PathParser().parsePathString(pathData).toNodes(),
                        fill = SolidColor(Color.White),
                    )
                }.build()
            }
            ReadOnlyProperty { _, _ -> vector.value }
        }
}
