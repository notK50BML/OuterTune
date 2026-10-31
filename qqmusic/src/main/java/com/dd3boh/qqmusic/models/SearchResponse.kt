package com.dd3boh.qqmusic.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parts of `musicu.fcg`'s `DoSearchForQQMusicDesktop` answer that are read: the songs, with the
 * numeric id the QRC download wants, the `mid` the LRC endpoint wants, and the length to match on.
 */
@Serializable
data class SearchResponse(
    val code: Int = 0,
    val req: Req? = null,
) {
    @Serializable
    data class Req(
        val code: Int = 0,
        val data: Data? = null,
    )

    @Serializable
    data class Data(
        val body: Body? = null,
    )

    @Serializable
    data class Body(
        val song: SongList? = null,
    )

    @Serializable
    data class SongList(
        val list: List<Song> = emptyList(),
    )

    @Serializable
    data class Song(
        val id: Long = 0,
        val mid: String = "",
        /** The plain name, e.g. "Shape Of You"; [title] adds the version, "Shape Of You (Elephante Remix)". */
        val name: String = "",
        val title: String = "",
        /** Length in seconds. */
        @SerialName("interval") val duration: Int = 0,
        val singer: List<Singer> = emptyList(),
        /** Other versions QQ folds under this one (live takes, other albums) - candidates too. */
        val grp: List<Song> = emptyList(),
    )

    @Serializable
    data class Singer(
        val name: String = "",
    )
}

/** `fcg_query_lyric_new.fcg`: the line-timed LRC, base64. A non-zero [retcode] means there is none. */
@Serializable
data class LrcResponse(
    val retcode: Int = 0,
    val code: Int = 0,
    val lyric: String? = null,
)
