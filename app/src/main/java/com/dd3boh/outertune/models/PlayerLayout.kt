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
         *
         * The "player area" this is measured against is the screen width/height minus the system
         * bars (status bar, nav bar, notch) on all sides, further reduced at the bottom by the
         * collapsed queue sheet's peek height. An editor rendering a preview against anything else
         * (the full physical screen, a fixed mock resolution, insets on only some sides) will
         * disagree with where the real player actually places a block - this was previously true of
         * the top inset specifically, which the app excluded from the horizontal calculation but not
         * this one, so yPercent=0 landed under the status bar rather than at the visible top edge.
         */
        val xPercent: Float = 50f,
        val yPercent: Float = 0f,
        val widthPercent: Float = 100f,
        /** Degrees clockwise. Only meaningful for the blocks the editor offers it on. */
        val rotationDegrees: Float = 0f,
        /**
         * Schema 4. Blocks sharing the same non-null [groupId] move, hide and show as one unit in
         * free placement - grouping [BlockId.CONTROL_MEMBERS]/[BlockId.ACTION_MEMBERS] back
         * together after splitting them out is exactly the "group/ungroup" the editor offers, not
         * a separate mechanism from the split itself.
         */
        val groupId: String? = null,
    )

    enum class BlockId(val key: String) {
        ARTWORK("artwork"),
        INFO("info"),
        PROGRESS("progress"),
        CONTROLS("controls"),
        ACTIONS("actions"),
        QUEUE("queue"),

        // Schema 4: individually-positionable/hideable members of CONTROLS/ACTIONS. A layout that
        // never mentions any of these keeps rendering CONTROLS/ACTIONS as the single grouped row
        // they always were - these only take over once a file actually asks for one of them, which
        // is what lets a v3 file import unchanged instead of needing migration.
        SHUFFLE("shuffle"),
        SEEK_BACKWARD("seek_backward"),
        SKIP_PREVIOUS("skip_previous"),
        PLAY_PAUSE("play_pause"),
        SEEK_FORWARD("seek_forward"),
        SKIP_NEXT("skip_next"),
        REPEAT("repeat"),
        SLEEP_TIMER("sleep_timer"),
        LIKE("like"),
        EQUALIZER("equalizer"),
        MENU("menu");

        companion object {
            fun from(key: String?) = entries.firstOrNull { it.key == key }

            /** The individually-positionable pieces [CONTROLS] splits into. */
            val CONTROL_MEMBERS = setOf(SHUFFLE, SEEK_BACKWARD, SKIP_PREVIOUS, PLAY_PAUSE, SEEK_FORWARD, SKIP_NEXT, REPEAT)

            /** The individually-positionable pieces [ACTIONS] splits into. */
            val ACTION_MEMBERS = setOf(SLEEP_TIMER, LIKE, EQUALIZER, MENU)
        }
    }

    fun block(id: BlockId): Block = blocks.firstOrNull { it.id == id } ?: Block(id)

    fun isVisible(id: BlockId): Boolean = block(id).visible

    /** True once the file asks for any individual member instead of the grouped [BlockId.CONTROLS] row. */
    val hasGranularControls: Boolean by lazy { blocks.any { it.id in BlockId.CONTROL_MEMBERS } }

    /** True once the file asks for any individual member instead of the grouped [BlockId.ACTIONS] row. */
    val hasGranularActions: Boolean by lazy { blocks.any { it.id in BlockId.ACTION_MEMBERS } }

    companion object {
        /** Bumped alongside the editor. A file claiming a newer version is refused, not guessed at. */
        const val SCHEMA_VERSION = 4

        const val DEFAULT_SPACING_DP = 16
        const val DEFAULT_SIDE_PADDING_DP = 24

        /**
         * The six top-level blocks every layout has always had. Deliberately not
         * [BlockId.entries] - that also includes the eleven granular members, and the built-in
         * default (no file imported at all) is exactly the "nobody asked for granular yet" case
         * that [hasGranularControls]/[hasGranularActions] exist to distinguish. Including them
         * here would make both permanently true from the moment the app starts.
         */
        val DEFAULT_BLOCKS: List<Block> =
            listOf(BlockId.ARTWORK, BlockId.INFO, BlockId.PROGRESS, BlockId.CONTROLS, BlockId.ACTIONS, BlockId.QUEUE)
                .map { Block(it) }

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
                    groupId = obj.optString("group").takeIf { it.isNotEmpty() },
                )
            }
            require(parsed.isNotEmpty()) { "None of the blocks in that file were recognised." }

            // Whether *this file* actually asked for granular controls/actions, judged from what
            // it explicitly listed - captured before backfilling below adds anything, since a
            // block added only because it was missing is not the file asking for it.
            val fileHasGranularControls = parsed.keys.any { it in BlockId.CONTROL_MEMBERS }
            val fileHasGranularActions = parsed.keys.any { it in BlockId.ACTION_MEMBERS }

            // A block the file never mentioned comes back with its defaults, at the end - dropping
            // it instead would silently remove a control (the play button, say) from a player the
            // user then has no way to fix from inside the app. But this only applies within a
            // group the file already granularized: backfilling every granular id unconditionally
            // is exactly what would make hasGranularControls/hasGranularActions permanently true
            // for every file, granular or not, which defeats the entire point of checking them.
            val blocks = parsed.values.toMutableList()
            BlockId.entries.forEach { id ->
                if (parsed.containsKey(id)) return@forEach
                val skip = (id in BlockId.CONTROL_MEMBERS && !fileHasGranularControls) ||
                    (id in BlockId.ACTION_MEMBERS && !fileHasGranularActions)
                if (!skip) blocks.add(Block(id))
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
