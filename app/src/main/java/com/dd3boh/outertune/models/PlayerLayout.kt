/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.models

import org.json.JSONObject

/**
 * A player layout, as produced by the standalone layout editor.
 *
 * Parsed with org.json rather than kotlinx.serialization on purpose. The :app module does not
 * apply the serialization plugin, and adding it to read one small file would pull a compiler
 * plugin and a runtime into the build for no benefit - org.json is part of Android.
 *
 * Everything here is defensive. This file arrives from outside the app, possibly hand-edited,
 * possibly from an older or newer editor, and the cost of getting it wrong is a player that
 * cannot be used to turn the music off. Any field that is missing, malformed or out of range
 * falls back to the built-in value rather than failing the whole layout.
 */
data class PlayerLayout(
    val mode: Mode = Mode.STACK,
    val spacingDp: Int = DEFAULT_SPACING_DP,
    val sidePaddingDp: Int = DEFAULT_SIDE_PADDING_DP,
    val blocks: List<Block> = DEFAULT_BLOCKS,
) {
    enum class Mode { STACK, FREE }

    enum class Align { START, CENTER }

    data class Block(
        val id: BlockId,
        val visible: Boolean = true,
        /** Artwork width as a percentage of the available width. */
        val sizePercent: Int = 100,
        /** Artwork corner radius in dp. */
        val radiusDp: Int = 12,
        /** Size multiplier as a percentage. */
        val scalePercent: Int = 100,
        val align: Align = Align.START,
        /**
         * Free placement, as percentages of the player area. Unused in [Mode.STACK].
         *
         * From schema 3 these are the block's *centre*, not its top-left corner: sliding a block
         * across the screen should not depend on how wide it happens to be, and a centred block
         * should stay centred when it is resized.
         */
        val xPercent: Float = 50f,
        val yPercent: Float = 0f,
        val widthPercent: Float = 100f,
        /** Degrees clockwise. Only meaningful for the blocks the editor offers it on. */
        val rotationDegrees: Float = 0f,
    )

    enum class BlockId(val key: String) {
        ARTWORK("artwork"),
        INFO("info"),
        PROGRESS("progress"),
        CONTROLS("controls"),
        ACTIONS("actions"),
        QUEUE("queue");

        companion object {
            fun from(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    fun block(id: BlockId): Block = blocks.firstOrNull { it.id == id } ?: Block(id)

    fun isVisible(id: BlockId): Boolean = block(id).visible

    companion object {
        /** Bumped alongside the editor. A file claiming a newer version is refused, not guessed at. */
        const val SCHEMA_VERSION = 3

        const val DEFAULT_SPACING_DP = 16
        const val DEFAULT_SIDE_PADDING_DP = 24

        val DEFAULT_BLOCKS: List<Block> = BlockId.entries.map { Block(it) }

        /** The built-in layout: what the player looks like with no file imported. */
        val DEFAULT = PlayerLayout()

        /**
         * @return the parsed layout, or a message explaining why the file was refused.
         */
        fun parse(json: String): Result<PlayerLayout> = runCatching {
            val root = JSONObject(json)

            val version = root.optInt("schemaVersion", 1)
            require(version <= SCHEMA_VERSION) {
                "This layout was made with a newer editor (v$version)."
            }

            val array = root.optJSONArray("blocks")
            require(array != null && array.length() > 0) { "That file has no blocks in it." }

            val parsed = LinkedHashMap<BlockId, Block>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = BlockId.from(obj.optString("id")) ?: continue
                // First occurrence wins. A duplicated id is a hand-editing slip, not a reason to
                // reject a file that is otherwise fine.
                if (parsed.containsKey(id)) continue
                parsed[id] = Block(
                    id = id,
                    visible = obj.optBoolean("visible", true),
                    sizePercent = obj.optInt("size", 100).coerceIn(20, 200),
                    radiusDp = obj.optInt("radius", 12).coerceIn(0, 400),
                    scalePercent = obj.optInt("scale", 100).coerceIn(50, 200),
                    align = if (obj.optString("align") == "center") Align.CENTER else Align.START,
                    xPercent = obj.optDouble("x", 50.0).toFloat().coerceIn(0f, 100f),
                    yPercent = obj.optDouble("y", 0.0).toFloat().coerceIn(0f, 100f),
                    widthPercent = obj.optDouble("w", 100.0).toFloat().coerceIn(5f, 100f),
                    rotationDegrees = obj.optDouble("rot", 0.0).toFloat().coerceIn(-90f, 90f),
                )
            }
            require(parsed.isNotEmpty()) { "None of the blocks in that file were recognised." }

            // A block the file never mentioned comes back with its defaults, at the end. Dropping
            // it instead would silently remove a control - the play button, say - from a player
            // the user then has no way to fix from inside the app.
            val blocks = parsed.values.toMutableList()
            BlockId.entries.forEach { id ->
                if (!parsed.containsKey(id)) blocks.add(Block(id))
            }

            PlayerLayout(
                mode = if (root.optString("mode") == "free") Mode.FREE else Mode.STACK,
                spacingDp = root.optInt("spacing", DEFAULT_SPACING_DP).coerceIn(0, 64),
                sidePaddingDp = root.optInt("sidePadding", DEFAULT_SIDE_PADDING_DP).coerceIn(0, 64),
                blocks = blocks,
            )
        }
    }
}
