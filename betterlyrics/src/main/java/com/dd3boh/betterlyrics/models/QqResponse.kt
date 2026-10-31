package com.dd3boh.betterlyrics.models

import kotlinx.serialization.Serializable

/** BetterLyrics' QQ Music relay: the song's QRC document, word-timed, as a string. */
@Serializable
data class QqResponse(
    val lyrics: String? = null,
)
