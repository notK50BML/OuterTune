/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

/**
 * One piece of the home-screen widget, and how many grid columns it may span.
 *
 * [flexible] pieces stretch to fill their cells; the rest are their own fixed size, centred in theirs.
 */
enum class WidgetElement(val flexible: Boolean, val defaultSpan: Int, val minSpan: Int, val maxSpan: Int) {
    COVER(false, 3, 2, 4),
    ARTIST(false, 2, 2, 3),
    INFO(true, 9, 4, 12),
    /** The line of the lyrics being sung, when the song has synced lyrics. */
    LYRICS(true, 12, 4, 12),
    UP_NEXT(true, 6, 3, 12),
    SEEK_BAR(true, 12, 4, 12),
    PREVIOUS(false, 2, 1, 3),
    PLAY_PAUSE(false, 2, 2, 3),
    NEXT(false, 2, 1, 3),
    SHUFFLE(false, 2, 1, 3),
    REPEAT(false, 2, 1, 3),
}

/**
 * How tall things are on the widget, in dp - shared by the widget and its editor, so the editor's
 * picture of the space is the widget's own rather than a uniform grid that looks nothing like it.
 */
object WidgetMetrics {
    const val COVER_MAX = 64
    private const val COVER_MIN = 28
    private const val ROW_GAP = 6

    /** Space around the content: less on a widget only a cell tall, where every dp counts. */
    fun padding(heightDp: Int) = if (heightDp < 110) 6 else 12

    fun heightOf(element: WidgetElement, small: Boolean, coverDp: Int): Int = when (element) {
        WidgetElement.COVER -> coverDp
        WidgetElement.ARTIST -> (coverDp * 2 / 3).coerceAtLeast(COVER_MIN)
        WidgetElement.INFO -> 46
        WidgetElement.LYRICS -> 44
        WidgetElement.UP_NEXT -> 20
        WidgetElement.SEEK_BAR -> 24
        WidgetElement.PLAY_PAUSE -> if (small) 46 else 56
        else -> if (small) 38 else 46
    }

    fun rowHeights(layout: WidgetLayout, small: Boolean, coverDp: Int): List<Int> =
        layout.rows.map { row -> row.maxOf { heightOf(it, small, coverDp) } + ROW_GAP }

    fun contentHeight(layout: WidgetLayout, small: Boolean, coverDp: Int, heightDp: Int) =
        rowHeights(layout, small, coverDp).sum() + 2 * padding(heightDp)

    /**
     * The biggest cover that fits: no wider than its cell, and small enough that every row still
     * fits the widget's height. A fixed 64dp cover was simply cut off on a narrow or short widget.
     */
    fun coverSize(layout: WidgetLayout, widthDp: Int, heightDp: Int, small: Boolean): Int {
        val cell = layout.placements.find { it.element == WidgetElement.COVER }
            ?.let { (widthDp - 2 * padding(heightDp)) * it.span / WidgetLayout.COLUMNS - 6 }
            ?: COVER_MAX
        var size = minOf(COVER_MAX, cell).coerceAtLeast(COVER_MIN)
        while (size > COVER_MIN && contentHeight(layout, small, size, heightDp) > heightDp) size -= 2
        return size
    }
}

/** Where one piece sits: its left column, its row, and how many columns it spans. */
data class WidgetPlacement(val element: WidgetElement, val col: Int, val row: Int, val span: Int) {
    val end: Int get() = col + span
    fun overlaps(other: WidgetPlacement) = row == other.row && col < other.end && other.col < end
}

/**
 * The widget's arrangement: a grid of [COLUMNS] columns and up to [MAX_ROWS] rows, each piece one row
 * tall and some columns wide.
 *
 * Pieces snap to cells and may never overlap - that is the whole of the placement rules, and
 * [normalised] enforces it however a layout was produced. Free placement on a grid is also exactly
 * what a home-screen widget can draw: each row becomes a line of cells whose widths are the spans,
 * so what the editor shows is what lands on the home screen.
 *
 * Stored as text, "COVER@0,0,3;INFO@3,0,9;...": element@col,row,span, separated by ';'. The older
 * rows-only format ("COVER,INFO|SEEK_BAR") still reads, laid out left to right, so an arrangement
 * saved by the previous editor carries over. Unknown names are dropped.
 */
data class WidgetLayout(
    val placements: List<WidgetPlacement>,
    /** Rows this arrangement may use: 1 for a small widget, 2 for a medium one, [MAX_ROWS] for large. */
    val maxRows: Int = MAX_ROWS,
) {

    /** Rows top to bottom, each left to right - the order the compact sizes and the widget read. */
    val rows: List<List<WidgetElement>>
        get() = placements.groupBy { it.row }.toSortedMap().values.map { row -> row.sortedBy { it.col }.map { it.element } }

    val elements: List<WidgetElement> get() = rows.flatten()

    operator fun contains(element: WidgetElement) = placements.any { it.element == element }

    val unused: List<WidgetElement> get() = WidgetElement.entries.filter { it !in this }

    fun encode(): String = placements.joinToString(";") { "${it.element.name}@${it.col},${it.row},${it.span}" }

    /** Drops duplicates, out-of-grid pieces and anything overlapping a piece placed before it. */
    fun normalised(): WidgetLayout {
        val kept = mutableListOf<WidgetPlacement>()
        for (p in placements) {
            val span = p.span.coerceIn(p.element.minSpan, p.element.maxSpan)
            val fixed = p.copy(span = span, col = p.col.coerceIn(0, COLUMNS - span))
            if (fixed.row !in 0 until maxRows) continue
            if (kept.any { it.element == fixed.element || it.overlaps(fixed) }) continue
            kept += fixed
        }
        if (kept.isEmpty()) return defaultFor(maxRows)
        // Close up empty rows, so a layout never has a blank band in the middle.
        val usedRows = kept.map { it.row }.distinct().sorted()
        return WidgetLayout(kept.map { p -> p.copy(row = usedRows.indexOf(p.row)) }, maxRows)
    }

    /** True if [placement] fits the grid without covering any other piece. */
    fun fits(placement: WidgetPlacement): Boolean =
        placement.col >= 0 && placement.end <= COLUMNS && placement.row in 0 until maxRows &&
            placements.none { it.element != placement.element && it.overlaps(placement) }

    /** [element] moved to ([col], [row]); unchanged if that would leave the grid or cover something. */
    fun move(element: WidgetElement, col: Int, row: Int): WidgetLayout {
        val current = placements.find { it.element == element } ?: return this
        val moved = current.copy(col = col, row = row)
        if (!fits(moved)) return this
        return copy(placements = placements.map { if (it.element == element) moved else it }).normalised()
    }

    /** [element] made [by] columns wider (or narrower), growing to the right, then to the left. */
    fun resize(element: WidgetElement, by: Int): WidgetLayout {
        val current = placements.find { it.element == element } ?: return this
        val span = (current.span + by).coerceIn(element.minSpan, element.maxSpan)
        if (span == current.span) return this
        val candidates = listOf(current.copy(span = span), current.copy(span = span, col = current.col - (span - current.span)))
        val resized = candidates.firstOrNull { fits(it) } ?: return this
        return copy(placements = placements.map { if (it.element == element) resized else it }).normalised()
    }

    fun remove(element: WidgetElement): WidgetLayout {
        val left = placements.filter { it.element != element }
        // Removing the last piece would leave nothing; that is not what was asked for.
        return if (left.isEmpty()) this else copy(placements = left).normalised()
    }

    /** [element] added in the first free spot that holds it at its default width, top-left first. */
    fun add(element: WidgetElement): WidgetLayout {
        if (element in this) return this
        val rowsInUse = (placements.maxOfOrNull { it.row } ?: -1) + 1
        for (row in 0 until minOf(rowsInUse + 1, maxRows)) {
            for (span in element.defaultSpan downTo element.minSpan) {
                for (col in 0..(COLUMNS - span)) {
                    val candidate = WidgetPlacement(element, col, row, span)
                    if (fits(candidate)) return copy(placements = placements + candidate).normalised()
                }
            }
        }
        return this
    }

    companion object {
        const val COLUMNS = 12
        const val MAX_ROWS = 4

        /**
         * Rows of pieces laid out at their default widths, each row centred - the controls sit in the
         * middle the way the player's do. Used for the defaults and the small widget sizes.
         */
        fun fromRows(rows: List<List<WidgetElement>>, maxRows: Int = MAX_ROWS): WidgetLayout {
            val placements = mutableListOf<WidgetPlacement>()
            rows.filter { it.isNotEmpty() }.take(maxRows).forEachIndexed { r, row ->
                var spans = row.map { it.defaultSpan }
                // Shrink the widest pieces until the row fits.
                while (spans.sum() > COLUMNS) {
                    val i = spans.indices.filter { spans[it] > row[it].minSpan }.maxByOrNull { spans[it] } ?: break
                    spans = spans.toMutableList().also { it[i]-- }
                }
                var col = ((COLUMNS - spans.sum()) / 2).coerceAtLeast(0)
                row.forEachIndexed { i, element ->
                    placements += WidgetPlacement(element, col, r, spans[i])
                    col += spans[i]
                }
            }
            return WidgetLayout(placements, maxRows).normalised()
        }

        /** Rows for each editable widget size. */
        const val SMALL_ROWS = 1
        const val MEDIUM_ROWS = 2

        /** One cell tall: the controls. */
        val DEFAULT_SMALL: WidgetLayout by lazy {
            fromRows(listOf(listOf(WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT)), SMALL_ROWS)
        }

        /** Two cells tall: cover and title above the controls. */
        val DEFAULT_MEDIUM: WidgetLayout by lazy {
            fromRows(
                listOf(
                    listOf(WidgetElement.COVER, WidgetElement.INFO),
                    listOf(
                        WidgetElement.SHUFFLE, WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE,
                        WidgetElement.NEXT, WidgetElement.REPEAT,
                    ),
                ),
                MEDIUM_ROWS,
            )
        }

        fun defaultFor(maxRows: Int): WidgetLayout = when {
            maxRows <= SMALL_ROWS -> DEFAULT_SMALL
            maxRows <= MEDIUM_ROWS -> DEFAULT_MEDIUM
            else -> DEFAULT
        }

        /** The full widget: the player's own order - cover and title, lyrics, seek bar, controls. */
        val DEFAULT: WidgetLayout by lazy {
            fromRows(
                listOf(
                    listOf(WidgetElement.COVER, WidgetElement.INFO),
                    listOf(WidgetElement.LYRICS),
                    listOf(WidgetElement.SEEK_BAR),
                    listOf(
                        WidgetElement.SHUFFLE, WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE,
                        WidgetElement.NEXT, WidgetElement.REPEAT,
                    ),
                )
            )
        }

        fun parse(text: String?, maxRows: Int = MAX_ROWS): WidgetLayout {
            if (text.isNullOrBlank()) return defaultFor(maxRows)
            if ('@' !in text) {
                // The previous editor's rows-only format.
                val rows = text.split('|').map { row ->
                    row.split(',').mapNotNull { name -> WidgetElement.entries.find { it.name == name.trim() } }
                }
                return fromRows(rows, maxRows)
            }
            val placements = text.split(';').mapNotNull { item ->
                val name = item.substringBefore('@').trim()
                val numbers = item.substringAfter('@', "").split(',').mapNotNull { it.trim().toIntOrNull() }
                val element = WidgetElement.entries.find { it.name == name } ?: return@mapNotNull null
                if (numbers.size != 3) return@mapNotNull null
                WidgetPlacement(element, numbers[0], numbers[1], numbers[2])
            }
            return WidgetLayout(placements, maxRows).normalised()
        }
    }
}
