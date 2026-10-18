/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextRotationAngledown
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes GeneratedIconPaths.kt: every desktop icon as the exact shape the phone app draws.
 *
 * Two sources, the same two the phone uses. Its own vector drawables, read from the app module's
 * res/drawable; and Google's Material Rounded icons, read out of the icon library this test (and
 * only this test) depends on. Everything is put on the drawables' 960-unit grid, so the icons sit
 * at one scale whichever source they came from.
 *
 * Run with OT_GEN_ICONS=1 when an icon is added; the output is committed, and the app never loads
 * the icon library.
 */
class IconGenerator {

    private val drawables = mapOf(
        "play" to "play", "pause" to "pause", "skipNext" to "skip_next", "skipPrevious" to "skip_previous",
        "shuffle" to "shuffle_on", "repeat" to "repeat_on", "repeatOne" to "repeat_one",
        "favorite" to "favorite", "favoriteBorder" to "favorite_border", "close" to "close",
        "queueMusic" to "queue_music", "download" to "download", "musicNote" to "music_note", "album" to "album",
    )

    private val material: Map<String, ImageVector> = mapOf(
        "fastForward" to Icons.Rounded.FastForward, "fastRewind" to Icons.Rounded.FastRewind,
        "equalizer" to Icons.Rounded.Equalizer, "expandLess" to Icons.Rounded.ExpandLess,
        "moreVert" to Icons.Rounded.MoreVert, "dragHandle" to Icons.Rounded.DragHandle,
        "bedtime" to Icons.Rounded.Bedtime, "accountCircle" to Icons.Rounded.AccountCircle,
        "person" to Icons.Rounded.Person, "logout" to Icons.AutoMirrored.Rounded.Logout,
        "key" to Icons.Rounded.Key, "networkCheck" to Icons.Rounded.NetworkCheck,
        "graphicEq" to Icons.Rounded.GraphicEq, "darkMode" to Icons.Rounded.DarkMode,
        "palette" to Icons.Rounded.Palette, "blurOn" to Icons.Rounded.BlurOn, "keyboard" to Icons.Rounded.Keyboard,
        "delete" to Icons.Rounded.Delete, "history" to Icons.Rounded.History,
        "restore" to Icons.Rounded.Restore, "timer" to Icons.Rounded.Timer,
        "cast" to Icons.Rounded.Cast, "castConnected" to Icons.Rounded.CastConnected,
        "stop" to Icons.Rounded.Stop, "devices" to Icons.Rounded.Devices,
        "home" to Icons.Rounded.Home, "search" to Icons.Rounded.Search,
        "settings" to Icons.Rounded.Settings, "share" to Icons.Rounded.Share,
        "playlistAdd" to Icons.AutoMirrored.Rounded.PlaylistAdd, "info" to Icons.Rounded.Info,
        "wifi" to Icons.Rounded.Wifi, "colorLens" to Icons.Rounded.ColorLens,
        "lyrics" to Icons.Rounded.Lyrics, "textRotationAngledown" to Icons.Rounded.TextRotationAngledown,
    )

    @Test
    fun generate() {
        assumeTrue(System.getenv("OT_GEN_ICONS") != null)
        val res = File("../app/src/main/res/drawable")
        val out = StringBuilder()
        out.appendLine("/*\n * Copyright (C) 2026 OuterTune Project\n *\n * SPDX-License-Identifier: GPL-3.0\n */\n")
        out.appendLine("package com.dd3boh.outertune.desktop\n")
        out.appendLine("/**")
        out.appendLine(" * The phone app's icons as path data on a 960-unit grid. Generated by IconGenerator - do not edit.")
        out.appendLine(" */")
        out.appendLine("internal object GeneratedIconPaths {")
        drawables.forEach { (name, file) ->
            val xml = File(res, "$file.xml").readText()
            val paths = Regex("android:pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
            out.appendLine("    const val $name = \"${paths.joinToString("")}\"")
        }
        material.forEach { (name, vector) ->
            val scale = 960f / vector.viewportWidth
            out.appendLine("    const val $name = \"${pathsOf(vector.root, scale)}\"")
        }
        out.appendLine("}")
        File("src/main/kotlin/com/dd3boh/outertune/desktop/GeneratedIconPaths.kt").writeText(out.toString())
    }

    private fun pathsOf(group: VectorGroup, scale: Float): String = buildString {
        group.forEach { node ->
            when (node) {
                is VectorPath -> append(node.pathData.joinToString("") { it.svg(scale) })
                is VectorGroup -> append(pathsOf(node, scale))
            }
        }
    }

    private fun f(v: Float) = ((v * 1000).toInt() / 1000f).toString().removeSuffix(".0")

    private fun PathNode.svg(s: Float): String = when (this) {
        is PathNode.Close -> "Z"
        is PathNode.MoveTo -> "M${f(x * s)},${f(y * s)}"
        is PathNode.RelativeMoveTo -> "m${f(dx * s)},${f(dy * s)}"
        is PathNode.LineTo -> "L${f(x * s)},${f(y * s)}"
        is PathNode.RelativeLineTo -> "l${f(dx * s)},${f(dy * s)}"
        is PathNode.HorizontalTo -> "H${f(x * s)}"
        is PathNode.RelativeHorizontalTo -> "h${f(dx * s)}"
        is PathNode.VerticalTo -> "V${f(y * s)}"
        is PathNode.RelativeVerticalTo -> "v${f(dy * s)}"
        is PathNode.CurveTo -> "C${f(x1 * s)},${f(y1 * s)} ${f(x2 * s)},${f(y2 * s)} ${f(x3 * s)},${f(y3 * s)}"
        is PathNode.RelativeCurveTo -> "c${f(dx1 * s)},${f(dy1 * s)} ${f(dx2 * s)},${f(dy2 * s)} ${f(dx3 * s)},${f(dy3 * s)}"
        is PathNode.ReflectiveCurveTo -> "S${f(x1 * s)},${f(y1 * s)} ${f(x2 * s)},${f(y2 * s)}"
        is PathNode.RelativeReflectiveCurveTo -> "s${f(dx1 * s)},${f(dy1 * s)} ${f(dx2 * s)},${f(dy2 * s)}"
        is PathNode.QuadTo -> "Q${f(x1 * s)},${f(y1 * s)} ${f(x2 * s)},${f(y2 * s)}"
        is PathNode.RelativeQuadTo -> "q${f(dx1 * s)},${f(dy1 * s)} ${f(dx2 * s)},${f(dy2 * s)}"
        is PathNode.ReflectiveQuadTo -> "T${f(x * s)},${f(y * s)}"
        is PathNode.RelativeReflectiveQuadTo -> "t${f(dx * s)},${f(dy * s)}"
        is PathNode.ArcTo -> "A${f(horizontalEllipseRadius * s)},${f(verticalEllipseRadius * s)} $theta " +
            "${if (isMoreThanHalf) 1 else 0},${if (isPositiveArc) 1 else 0} ${f(arcStartX * s)},${f(arcStartY * s)}"
        is PathNode.RelativeArcTo -> "a${f(horizontalEllipseRadius * s)},${f(verticalEllipseRadius * s)} $theta " +
            "${if (isMoreThanHalf) 1 else 0},${if (isPositiveArc) 1 else 0} ${f(arcStartDx * s)},${f(arcStartDy * s)}"
    }
}
