/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull

/**
 * The queue as text, for keeping it between sessions - the phone's persistent queue.
 *
 * Only what is needed to show and play it again: each song's id, title, credits, cover, length and
 * album, the order, where in it playback was and how far into the song. Written as a plain JSON tree
 * (no serialisation plugin in this module), and read defensively - a saved queue that does not
 * parse is simply not restored.
 */
internal object QueueSnapshot {

    fun encode(state: QueueState, positionMs: Long): String {
        val songs = JsonArray(state.songs.map { song ->
            JsonObject(buildMap {
                put("id", JsonPrimitive(song.id))
                put("title", JsonPrimitive(song.title))
                put("thumbnail", JsonPrimitive(song.thumbnail))
                song.duration?.let { put("duration", JsonPrimitive(it)) }
                song.album?.let {
                    put("albumId", JsonPrimitive(it.id))
                    put("albumName", JsonPrimitive(it.name))
                }
                put("artists", JsonArray(song.artists.map { a ->
                    JsonObject(buildMap {
                        put("name", JsonPrimitive(a.name))
                        a.id?.let { put("id", JsonPrimitive(it)) }
                    })
                }))
            })
        })
        return JsonObject(mapOf(
            "songs" to songs,
            "index" to JsonPrimitive(state.index),
            "order" to JsonArray(state.order.map { JsonPrimitive(it) }),
            "orderPosition" to JsonPrimitive(state.orderPosition),
            "shuffled" to JsonPrimitive(state.shuffled),
            "repeat" to JsonPrimitive(state.repeat.name),
            "title" to JsonPrimitive(state.title),
            "positionMs" to JsonPrimitive(positionMs),
        )).toString()
    }

    fun decode(text: String): Pair<QueueState, Long>? = runCatching {
        if (text.isBlank()) return null
        val root = Json.parseToJsonElement(text).jsonObject
        val songs = root.getValue("songs").jsonArray.map { element ->
            val o = element.jsonObject
            SongItem(
                id = o.getValue("id").jsonPrimitive.content,
                title = o.getValue("title").jsonPrimitive.content,
                artists = o["artists"]?.jsonArray.orEmpty().map { a ->
                    val ao = a.jsonObject
                    Artist(name = ao.getValue("name").jsonPrimitive.content, id = ao["id"]?.jsonPrimitive?.contentOrNull)
                },
                album = o["albumId"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    com.zionhuang.innertube.models.Album(name = o["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty(), id = id)
                },
                duration = o["duration"]?.jsonPrimitive?.intOrNull,
                thumbnail = o["thumbnail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val state = QueueState(
            songs = songs,
            index = root.getValue("index").jsonPrimitive.int,
            order = root.getValue("order").jsonArray.map { it.jsonPrimitive.int },
            orderPosition = root.getValue("orderPosition").jsonPrimitive.int,
            shuffled = root["shuffled"]?.jsonPrimitive?.contentOrNull == "true",
            repeat = RepeatMode.entries.firstOrNull { it.name == root["repeat"]?.jsonPrimitive?.contentOrNull } ?: RepeatMode.OFF,
            title = root["title"]?.jsonPrimitive?.contentOrNull ?: "Queue",
        )
        state to (root["positionMs"]?.jsonPrimitive?.long ?: 0L)
    }.getOrNull()?.takeIf { (state, _) -> state.current != null && state.order.size == state.songs.size }
}
