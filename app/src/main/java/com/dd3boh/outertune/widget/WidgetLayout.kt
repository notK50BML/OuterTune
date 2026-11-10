/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One piece of the home-screen widget, and how many columns it may span - in a 12-column grid; a
 * coarser or finer grid scales these (see [WidgetLayout.minSpan] and friends).
 *
 * [flexible] pieces stretch to fill their cells; the rest are their own fixed size, centred in theirs.
 */
enum class WidgetElement(val flexible: Boolean, val defaultSpan: Int, val minSpan: Int, val maxSpan: Int) {
    COVER(false, 3, 2, 4),
    ARTIST(false, 2, 2, 3),
    INFO(true, 9, 4, 12),
    /** The lyrics being sung, with the lines either side, when the song has synced lyrics. */
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
        // Three lines: the one before, the one being sung, the one after.
        WidgetElement.LYRICS -> 64
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
            ?.let { (widthDp - 2 * padding(heightDp)) * it.span / layout.columns - 6 }
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
 * The widget's arrangement: a grid of [columns] columns and up to [maxRows] rows, each piece one
 * row tall and some columns wide.
 *
 * Pieces snap to cells and may never overlap - that is the whole of the placement rules, and
 * [normalised] enforces it however a layout was produced. Free placement on a grid is also exactly
 * what a home-screen widget can draw: each row becomes a line of cells whose widths are the spans,
 * so what the editor shows is what lands on the home screen.
 *
 * Stored as text, "grid=16x3;COVER@0,0,3;INFO@3,0,9;...": the grid, then element@col,row,span,
 * separated by ';'. Without a grid it is the original 12 columns; the older rows-only format
 * ("COVER,INFO|SEEK_BAR") still reads too, laid out left to right. Unknown names are dropped.
 */
data class WidgetLayout(
    val placements: List<WidgetPlacement>,
    /** Rows this arrangement may use. */
    val maxRows: Int = MAX_ROWS,
    /** Columns across: [COLUMNS], or one of the other [GRIDS]. */
    val columns: Int = COLUMNS,
) {

    /** Rows top to bottom, each left to right - the order the compact sizes and the widget read. */
    val rows: List<List<WidgetElement>>
        get() = placements.groupBy { it.row }.toSortedMap().values.map { row -> row.sortedBy { it.col }.map { it.element } }

    val elements: List<WidgetElement> get() = rows.flatten()

    operator fun contains(element: WidgetElement) = placements.any { it.element == element }

    val unused: List<WidgetElement> get() = WidgetElement.entries.filter { it !in this }

    // A piece's limits, scaled from the 12-column figures. The minimum rounds up so a button never
    // gets a cell too narrow to hold it.
    fun minSpan(e: WidgetElement) = ceil(e.minSpan * columns / COLUMNS.toFloat()).toInt().coerceIn(1, columns)
    fun maxSpan(e: WidgetElement) = (e.maxSpan * columns / COLUMNS).coerceIn(minSpan(e), columns)
    fun defaultSpan(e: WidgetElement) = (e.defaultSpan * columns / COLUMNS.toFloat()).roundToInt().coerceIn(minSpan(e), maxSpan(e))

    fun encode(): String {
        val items = placements.joinToString(";") { "${it.element.name}@${it.col},${it.row},${it.span}" }
        return "grid=${columns}x$maxRows;$items"
    }

    /** Drops duplicates, out-of-grid pieces and anything overlapping a piece placed before it. */
    fun normalised(): WidgetLayout {
        val kept = mutableListOf<WidgetPlacement>()
        for (p in placements) {
            val span = p.span.coerceIn(minSpan(p.element), maxSpan(p.element))
            val fixed = p.copy(span = span, col = p.col.coerceIn(0, columns - span))
            if (fixed.row !in 0 until maxRows) continue
            if (kept.any { it.element == fixed.element || it.overlaps(fixed) }) continue
            kept += fixed
        }
        if (kept.isEmpty()) return defaultFor(maxRows, columns)
        // Close up empty rows, so a layout never has a blank band in the middle.
        val usedRows = kept.map { it.row }.distinct().sorted()
        return copy(placements = kept.map { p -> p.copy(row = usedRows.indexOf(p.row)) })
    }

    private fun with(list: List<WidgetPlacement>) = copy(placements = list).normalised()

    /** True if [placement] fits the grid without covering any other piece. */
    fun fits(placement: WidgetPlacement): Boolean =
        placement.col >= 0 && placement.end <= columns && placement.row in 0 until maxRows &&
            placements.none { it.element != placement.element && it.overlaps(placement) }

    /**
     * [element] dropped at ([col], [row]), or null if there is no way to make room.
     *
     * - An empty spot: it moves there.
     * - Mostly on top of one other piece: the two swap places.
     * - Partly over its neighbours: they are pushed along the row, if the row has the room. That is
     *   what makes nudging a row of buttons along one at a time work in any order.
     */
    fun drop(element: WidgetElement, col: Int, row: Int): WidgetLayout? {
        val current = placements.find { it.element == element } ?: return null
        if (row !in 0 until maxRows) return null
        val moved = current.copy(col = col.coerceIn(0, columns - current.span), row = row)
        if (moved == current) return this
        val others = placements.filter { it.element != element }
        val hit = others.filter { it.overlaps(moved) }
        if (hit.isEmpty()) return with(others + moved)
        if (hit.size == 1) {
            val other = hit.single()
            val overlap = minOf(moved.end, other.end) - maxOf(moved.col, other.col)
            if (overlap * 2 > moved.span) swap(current, other)?.let { return it }
        }
        return push(others, moved)
    }

    /** [a] and [b] in each other's places, or null if their widths will not allow it. */
    private fun swap(a: WidgetPlacement, b: WidgetPlacement): WidgetLayout? {
        val rest = placements.filter { it.element != a.element && it.element != b.element }
        fun ok(x: WidgetPlacement, y: WidgetPlacement) =
            x.col >= 0 && x.end <= columns && y.col >= 0 && y.end <= columns && !x.overlaps(y) &&
                rest.none { it.overlaps(x) || it.overlaps(y) }

        // Each in the other's spot, kept inside the grid.
        val a1 = a.copy(col = b.col.coerceIn(0, columns - a.span), row = b.row)
        val b1 = b.copy(col = a.col.coerceIn(0, columns - b.span), row = a.row)
        if (ok(a1, b1)) return with(rest + a1 + b1)

        // Side by side in one row but of different widths: swap their order within the space the
        // two of them cover.
        if (a.row == b.row) {
            val left = minOf(a.col, b.col)
            val right = maxOf(a.end, b.end)
            val (a2, b2) = if (a.col < b.col) {
                a.copy(col = right - a.span) to b.copy(col = left)
            } else {
                a.copy(col = left) to b.copy(col = right - b.span)
            }
            if (ok(a2, b2)) return with(rest + a2 + b2)
        }
        return null
    }

    /** [moved] placed, the pieces it lands on pushed aside along its row; null if they run out of room. */
    private fun push(others: List<WidgetPlacement>, moved: WidgetPlacement): WidgetLayout? {
        val inRow = others.filter { it.row == moved.row }
        val centre = moved.col + moved.span / 2f
        val result = others.filter { it.row != moved.row }.toMutableList()

        var cursor = moved.end
        for (p in inRow.filter { it.col + it.span / 2f >= centre }.sortedBy { it.col }) {
            val col = maxOf(p.col, cursor)
            if (col + p.span > columns) return null
            result += p.copy(col = col)
            cursor = col + p.span
        }
        cursor = moved.col
        for (p in inRow.filter { it.col + it.span / 2f < centre }.sortedByDescending { it.col }) {
            val end = minOf(p.end, cursor)
            if (end - p.span < 0) return null
            result += p.copy(col = end - p.span)
            cursor = end - p.span
        }
        return with(result + moved)
    }

    /** [element] made [by] columns wider (or narrower), growing to the right, then to the left. */
    fun resize(element: WidgetElement, by: Int): WidgetLayout {
        val current = placements.find { it.element == element } ?: return this
        val span = (current.span + by).coerceIn(minSpan(element), maxSpan(element))
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
            for (span in defaultSpan(element) downTo minSpan(element)) {
                for (col in 0..(columns - span)) {
                    val candidate = WidgetPlacement(element, col, row, span)
                    if (fits(candidate)) return copy(placements = placements + candidate).normalised()
                }
            }
        }
        return this
    }

    /**
     * The same arrangement on another grid, row by row. Each row keeps its pieces in their order and
     * the empty space between them in the same proportions, so pieces side by side stay side by
     * side and a centred row stays centred. A piece at (or one off) its default width takes the new
     * grid's default, so switching back and forth does not creep; other widths scale. A row that no
     * longer fits gives up width from its widest pieces, and only then its last pieces.
     */
    fun regridded(newColumns: Int, newRows: Int): WidgetLayout {
        if (newColumns == columns && newRows == maxRows) return this
        val target = WidgetLayout(emptyList(), newRows, newColumns)
        val ratio = newColumns / columns.toDouble()
        val result = mutableListOf<WidgetPlacement>()
        placements.filter { it.row < newRows }.groupBy { it.row }.forEach { (_, rowPieces) ->
            val pieces = rowPieces.sortedBy { it.col }.toMutableList()
            // The gap before each piece, and after the last.
            val gaps = mutableListOf<Int>()
            var previousEnd = 0
            pieces.forEach { gaps += it.col - previousEnd; previousEnd = it.end }
            gaps += columns - previousEnd

            val spans = pieces.map { p ->
                if (abs(p.span - defaultSpan(p.element)) <= 1) target.defaultSpan(p.element)
                else ceil(p.span * ratio - 0.5).toInt().coerceIn(target.minSpan(p.element), target.maxSpan(p.element))
            }.toMutableList()
            while (spans.sum() > newColumns) {
                val widest = spans.indices.filter { spans[it] > target.minSpan(pieces[it].element) }.maxByOrNull { spans[it] }
                if (widest == null) {
                    pieces.removeAt(pieces.lastIndex); spans.removeAt(spans.lastIndex); gaps.removeAt(gaps.lastIndex)
                } else spans[widest]--
            }
            if (pieces.isEmpty()) return@forEach
            if (gaps.first() == gaps.last()) target.evenOut(spans, pieces.map { it.element })

            // The new empty space, shared out in the old proportions; leftovers by largest remainder.
            val free = newColumns - spans.sum()
            val oldFree = gaps.sum()
            val exact = DoubleArray(gaps.size)
            if (oldFree > 0) gaps.forEachIndexed { i, g -> exact[i] = g * free / oldFree.toDouble() }
            else { exact[0] += free / 2.0; exact[gaps.lastIndex] += free / 2.0 }
            val share = IntArray(gaps.size) { kotlin.math.floor(exact[it] + 1e-9).toInt() }
            var given = share.sum()
            val byRemainder = gaps.indices.sortedByDescending { exact[it] - share[it] }
            var k = 0
            while (given < free) { share[byRemainder[k % gaps.size]]++; given++; k++ }

            var col = 0
            pieces.forEachIndexed { i, p ->
                col += share[i]
                result += p.copy(col = col, span = spans[i])
                col += spans[i]
            }
        }
        return target.copy(placements = result).normalised()
    }

    /**
     * Makes the space [spans] leave in a row even, so a centred row sits exactly in the middle rather
     * than half a cell off: the middle-most piece that can grows by a column, or else one shrinks.
     */
    private fun evenOut(spans: MutableList<Int>, elements: List<WidgetElement>) {
        if ((columns - spans.sum()) % 2 == 0) return
        val middleFirst = spans.indices.sortedBy { abs(it - (spans.size - 1) / 2f) }
        middleFirst.firstOrNull { spans[it] < maxSpan(elements[it]) && spans.sum() < columns }?.let { spans[it]++; return }
        middleFirst.firstOrNull { spans[it] > minSpan(elements[it]) }?.let { spans[it]-- }
    }

    companion object {
        const val COLUMNS = 12
        const val MAX_ROWS = 4

        /** The grids on offer: big cells, the usual, and small cells (with a row more). */
        val GRIDS = listOf(8, COLUMNS, 16)

        /** The most columns any grid has - see the widget_cell_* layouts. */
        const val MAX_COLUMNS = 16

        /** Rows for each editable widget size, on the usual grid. */
        const val SMALL_ROWS = 1
        const val MEDIUM_ROWS = 3

        /** Rows a size gets on a grid: the finest grid fits one more row. */
        fun rowsFor(baseRows: Int, columns: Int) = if (columns > COLUMNS) baseRows + 1 else baseRows

        /**
         * Rows of pieces laid out at their default widths, each row centred - the controls sit in the
         * middle the way the player's do. Used for the defaults and the small widget sizes.
         */
        fun fromRows(rows: List<List<WidgetElement>>, maxRows: Int = MAX_ROWS, columns: Int = COLUMNS): WidgetLayout {
            val grid = WidgetLayout(emptyList(), maxRows, columns)
            val placements = mutableListOf<WidgetPlacement>()
            rows.filter { it.isNotEmpty() }.take(maxRows).forEachIndexed { r, row ->
                var spans = row.map { grid.defaultSpan(it) }
                // Shrink the widest pieces until the row fits.
                while (spans.sum() > columns) {
                    val i = spans.indices.filter { spans[it] > grid.minSpan(row[it]) }.maxByOrNull { spans[it] } ?: break
                    spans = spans.toMutableList().also { it[i]-- }
                }
                // An odd leftover would leave a centred row half a cell off.
                grid.evenOut(spans.toMutableList().also { spans = it }, row)
                var col = ((columns - spans.sum()) / 2).coerceAtLeast(0)
                row.forEachIndexed { i, element ->
                    placements += WidgetPlacement(element, col, r, spans[i])
                    col += spans[i]
                }
            }
            return grid.copy(placements = placements).normalised()
        }

        private val SMALL_PIECES = listOf(listOf(WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT))
        private val CONTROLS = listOf(
            WidgetElement.SHUFFLE, WidgetElement.PREVIOUS, WidgetElement.PLAY_PAUSE, WidgetElement.NEXT, WidgetElement.REPEAT,
        )
        private val MEDIUM_PIECES = listOf(listOf(WidgetElement.COVER, WidgetElement.INFO), listOf(WidgetElement.SEEK_BAR), CONTROLS)
        private val LARGE_PIECES = listOf(
            listOf(WidgetElement.COVER, WidgetElement.INFO),
            listOf(WidgetElement.LYRICS),
            listOf(WidgetElement.SEEK_BAR),
            CONTROLS,
        )

        /** One cell tall: the controls. */
        val DEFAULT_SMALL: WidgetLayout by lazy { fromRows(SMALL_PIECES, SMALL_ROWS) }

        /** Two cells tall: cover and title, a thin seek bar, and the controls. */
        val DEFAULT_MEDIUM: WidgetLayout by lazy { fromRows(MEDIUM_PIECES, MEDIUM_ROWS) }

        /** The full widget: the player's own order - cover and title, lyrics, seek bar, controls. */
        val DEFAULT: WidgetLayout by lazy { fromRows(LARGE_PIECES) }

        /** The default arrangement for a size of [maxRows] rows on a grid of [columns]. */
        fun defaultFor(maxRows: Int, columns: Int = COLUMNS): WidgetLayout {
            // The size's usual row count: the finest grid has one row more than it.
            val baseRows = if (columns > COLUMNS) maxRows - 1 else maxRows
            val pieces = when {
                baseRows <= SMALL_ROWS -> SMALL_PIECES
                baseRows <= MEDIUM_ROWS -> MEDIUM_PIECES
                else -> LARGE_PIECES
            }
            return fromRows(pieces, maxRows, columns)
        }

        /**
         * Reads a stored arrangement for a size whose usual row count is [baseRows]. The grid stored
         * with it decides its columns, and so its rows; without one it is the usual 12 columns.
         */
        fun parse(text: String?, baseRows: Int = MAX_ROWS): WidgetLayout {
            if (text.isNullOrBlank()) return defaultFor(baseRows)
            if ('@' !in text) {
                // The first editor's rows-only format.
                val rows = text.split('|').map { row ->
                    row.split(',').mapNotNull { name -> WidgetElement.entries.find { it.name == name.trim() } }
                }
                return fromRows(rows, baseRows)
            }
            val items = text.split(';')
            val columns = items.firstOrNull { it.startsWith("grid=") }
                ?.removePrefix("grid=")?.substringBefore('x')?.toIntOrNull()?.takeIf { it in GRIDS } ?: COLUMNS
            val placements = items.mapNotNull { item ->
                val name = item.substringBefore('@').trim()
                val numbers = item.substringAfter('@', "").split(',').mapNotNull { it.trim().toIntOrNull() }
                val element = WidgetElement.entries.find { it.name == name } ?: return@mapNotNull null
                if (numbers.size != 3) return@mapNotNull null
                WidgetPlacement(element, numbers[0], numbers[1], numbers[2])
            }
            return WidgetLayout(placements, rowsFor(baseRows, columns), columns).normalised()
        }
    }
}
