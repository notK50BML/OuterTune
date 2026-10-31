/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * Keys for a lazy list that can hold the same thing twice - a song queued twice, or a playlist with
 * a repeat in it. Keyed by id alone, the second copy reused the first one's key and the list threw
 * "Key was already used", taking the window down with it.
 *
 * The first copy keeps its plain id, so a row still follows its song across a reorder; later
 * copies get "#2", "#3" after it, counted in list order.
 */
internal fun <T> uniqueKeys(items: List<T>, id: (T) -> Any): List<Any> {
    val seen = HashMap<Any, Int>()
    return items.map { item ->
        val key = id(item)
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        if (count == 1) key else "$key#$count"
    }
}
