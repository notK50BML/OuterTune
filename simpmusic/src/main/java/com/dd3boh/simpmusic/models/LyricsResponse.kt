package com.dd3boh.simpmusic.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LyricsResponse(
    val type: String? = null,
    val data: List<LyricsData> = emptyList(),
) {
    val isSuccess: Boolean
        get() = type == "success"
}

@Serializable
data class LyricsData(
    val videoId: String? = null,
    @SerialName("durationSeconds")
    val duration: Int? = null,
    val syncedLyrics: String? = null,
    @SerialName("plainLyric")
    val plainLyrics: String? = null,
    val richSyncLyrics: String? = null,
)

/** The body of an error answer: `{"success": false, "error": {"code": 403, "reason": "..."}}`. */
@Serializable
data class ErrorResponse(
    val success: Boolean? = null,
    val error: ErrorDetail? = null,
)

@Serializable
data class ErrorDetail(
    val code: Int? = null,
    val reason: String? = null,
)
