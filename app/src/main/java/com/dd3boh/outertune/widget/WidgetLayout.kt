/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

/**
 * One piece of the home-screen widget.
 *
 * [flexible] pieces stretch to share whatever width their row has left; the rest are their own
 * fixed size. That split is the whole of the placement rules - see [WidgetLayout].
 */
enum class WidgetElement(val flexible: Boolean) {
    COVER(false),
    ARTIST(false),
    INFO(true),
    UP_NEXT(true),
    SEEK_BAR(true),
    PREVIOUS(false),
    PLAY_PAUSE(false),
    NEXT(false),
    SHUFFLE(false),
    REPEAT(false),
}

/**
 * The widget's arrangement: rows, top to bottom, each an ordered list of elements, left to right.
 *
 * Rows of slots rather than free positions, which is what makes layering impossible by
 * construction - nothing can be placed on top of anything else, only beside it. It is also the only
 * thing a home-screen widget can actually do: RemoteViews has no free placement, but it can nest
 * rows. The rules, all enforced by [normalised]:
 *
 * - Every element appears at most once.
 * - At most [MAX_ROWS] rows and [MAX_PER_ROW] elements in a row, so a row always fits.
 * - No empty rows, and never an empty layout - that falls back to [DEFAULT].
 *
 * Stored as text, "COVER,INFO,PLAY_PAUSE|SEEK_BAR": rows split by '|', elements by ','. Unknown
 * names are dropped, so a layout written by a newer version still loads.
 */
data class WidgetLayout(val rows: List<List<WidgetElement>>) {

    fun encode(): String = rows.joinToString("|") { row -> row.joinToString(",") { it.name } }

    val unused: List<WidgetElement> get() = WidgetElement.entries.filter { element -> rows.none { element in it } }

    fun normalised(): WidgetLayout {
        val seen = mutableSetOf<WidgetElement>()
        val cleaned = rows
            .map { row -> row.filter { seen.add(it) }.take(MAX_PER_ROW) }
            .filter { it.isNotEmpty() }
            .take(MAX_ROWS)
        return if (cleaned.isEmpty()) DEFAULT else WidgetLayout(cleaned)
    }

    private fun locate(element: WidgetElement): Pair<Int, Int>? {
        rows.forEachIndexed { r, row -> row.indexOf(element).takeIf { it >= 0 }?.let { return r to it } }
        return null
    }

    /** Swaps [element] with its neighbour, [by] = -1 left or +1 right. */
    fun moveWithinRow(element: WidgetElement, by: Int): WidgetLayout {
        val (r, i) = locate(element) ?: return this
        val target = i + by
        if (target !in rows[r].indices) return this
        val row = rows[r].toMutableList().apply { add(target, removeAt(i)) }
        return WidgetLayout(rows.toMutableList().apply { this[r] = row }).normalised()
    }

    /**
     * Moves [element] to the row above ([by] = -1) or below (+1), at the end. Moving down from the
     * last row starts a new one, while there is room for it. Refused when the row is full.
     */
    fun moveToRow(element: WidgetElement, by: Int): WidgetLayout {
        val (r, _) = locate(element) ?: return this
        val target = r + by
        if (target < 0) return this
        val newRows = rows.map { it.toMutableList() }.toMutableList()
        if (target >= newRows.size) {
            if (newRows.size >= MAX_ROWS || newRows[r].size == 1) return this
            newRows.add(mutableListOf())
        }
        if (newRows[target].size >= MAX_PER_ROW) return this
        newRows[r].remove(element)
        newRows[target].add(element)
        return WidgetLayout(newRows).normalised()
    }

    fun remove(element: WidgetElement): WidgetLayout {
        val removed = WidgetLayout(rows.map { row -> row.filter { it != element } })
        // Removing the last element would leave nothing; the default is not what was asked for.
        return if (removed.rows.all { it.isEmpty() }) this else removed.normalised()
    }

    /** Adds [element] to the end of row [row], or a new last row if [row] is past the end. */
    fun add(element: WidgetElement, row: Int): WidgetLayout {
        if (element !in unused) return this
        val newRows = rows.map { it.toMutableList() }.toMutableList()
        if (row >= newRows.size) {
            if (newRows.size >= MAX_ROWS) return this
            newRows.add(mutableListOf())
        }
        val target = row.coerceAtMost(newRows.lastIndex)
        if (newRows[target].size >= MAX_PER_ROW) return this
        newRows[target].add(element)
        return WidgetLayout(newRows).normalised()
    }

    companion object {
        const val MAX_ROWS = 3
        const val MAX_PER_ROW = 6

        /** What the widget looked like before it could be arranged. */
        val DEFAULT = WidgetLayout(
            listOf(
                listOf(WidgetElement.COVER, WidgetElement.INFO, WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT),
                listOf(WidgetElement.SEEK_BAR),
            )
        )

        fun parse(text: String?): WidgetLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val rows = text.split('|').map { row ->
                row.split(',').mapNotNull { name -> WidgetElement.entries.find { it.name == name.trim() } }
            }
            return WidgetLayout(rows).normalised()
        }
    }
}
