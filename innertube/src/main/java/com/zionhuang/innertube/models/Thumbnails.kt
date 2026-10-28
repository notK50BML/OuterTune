package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    // Defaulted: YouTube now sends some thumbnail objects with no list in them, and a required
    // field failed the whole response - a radio's next page among them.
    val thumbnails: List<Thumbnail> = emptyList(),
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
)
