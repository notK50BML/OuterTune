/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A player layout from the layout editor (tools/layout-editor.html), Windows edition.
 *
 * The same file format as the phone's (app: models/PlayerLayout.kt), with `"platform": "windows"`:
 * every block is placed freely in the window - its centre at (x%, y%) of the player area, w% of its
 * width - which is what a landscape window wants rather than the phone's vertical stack. A phone
 * layout is refused here and a Windows one there, because the same percentages describe a tall
 * phone and a wide window completely differently.
 *
 * Defensive throughout, as on the phone: anything missing or out of range falls back to the
 * default rather than failing, and the player can always still be used to stop the music.
 */
data class PlayerLayout(val blocks: List<Block>) {

    data class Block(
        val id: String,
        val visible: Boolean = true,
        /** Centre, as percentages of the player area. */
        val x: Float = 50f,
        val y: Float = 50f,
        /** Width as a percentage of the player area. */
        val w: Float = 30f,
        val scale: Float = 1f,
        val radiusDp: Int = 12,
        val centred: Boolean = false,
        val rotation: Float = 0f,
    )

    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA_VERSION = 4
        const val PLATFORM = "windows"

        val CONTROL_MEMBERS = listOf("shuffle", "seek_backward", "skip_previous", "play_pause", "seek_forward", "skip_next", "repeat")
        val ACTION_MEMBERS = listOf("sleep_timer", "like", "equalizer", "menu")
        val ALL = listOf("artwork", "info", "progress", "controls", "actions", "queue") + CONTROL_MEMBERS + ACTION_MEMBERS

        /** The message for a file that is not a Windows layout - or the layout. */
        fun parse(text: String): Result<PlayerLayout> = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
            require(version <= SCHEMA_VERSION) { "This layout was made with a newer editor (v$version)." }
            require(root["platform"]?.jsonPrimitive?.contentOrNull == PLATFORM) {
                "That layout is for the phone. In the editor, switch to Windows and design one for the window."
            }
            val array = root["blocks"]?.jsonArray
            require(!array.isNullOrEmpty()) { "That file has no blocks in it." }
            val seen = LinkedHashMap<String, Block>()
            for (element in array) {
                val o = element as? JsonObject ?: continue
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (id !in ALL || id in seen) continue
                fun f(k: String, d: Double) = o[k]?.jsonPrimitive?.doubleOrNull ?: d
                seen[id] = Block(
                    id = id,
                    visible = o["visible"]?.jsonPrimitive?.booleanOrNull ?: true,
                    x = f("x", 50.0).toFloat().coerceIn(0f, 100f),
                    y = f("y", 50.0).toFloat().coerceIn(0f, 100f),
                    w = f("w", 30.0).toFloat().coerceIn(3f, 100f),
                    scale = (f("scale", 100.0) / 100.0).toFloat().coerceIn(0.5f, 2f),
                    radiusDp = f("radius", 12.0).toInt().coerceIn(0, 400),
                    centred = o["align"]?.jsonPrimitive?.contentOrNull == "center",
                    rotation = f("rot", 0.0).toFloat().coerceIn(-90f, 90f),
                )
            }
            require(seen.isNotEmpty()) { "None of the blocks in that file were recognised." }
            // A layout that never placed the play button still gets one: a player that cannot be
            // paused from inside the app is not a layout anyone wants, whatever the file says.
            val granular = seen.keys.any { it in CONTROL_MEMBERS }
            if (granular && "play_pause" !in seen) seen["play_pause"] = DEFAULT.block("play_pause")!!
            if (!granular && "controls" !in seen) seen["controls"] = DEFAULT.block("controls")!!
            PlayerLayout(seen.values.toList())
        }

        /**
         * What the desktop player looks like with no layout imported, written as a layout - the
         * editor's Windows starting point, so a design begins from the player as it is.
         */
        val DEFAULT = PlayerLayout(
            listOf(
                Block("artwork", x = 26f, y = 50f, w = 34f, radiusDp = 16),
                Block("info", x = 70f, y = 38f, w = 44f),
                Block("actions", x = 70f, y = 38f, w = 44f),
                Block("progress", x = 70f, y = 49f, w = 44f),
                Block("controls", x = 70f, y = 60f, w = 44f),
                Block("queue", x = 92f, y = 96f, w = 14f),
            )
        )
    }
}
