package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded

@Immutable
/** One artist's plays and listening time in a period - Stats' artist row. */
data class ArtistPlayStats(
    @Embedded
    val artist: ArtistEntity,
    val totalPlayTime: Long,
    val totalPlays: Int,
)

data class Artist(
    @Embedded
    val artist: ArtistEntity,
    val songCount: Int,
    val downloadCount: Int
) : LocalItem() {
    override val id: String
        get() = artist.id
    override val title: String
        get() = artist.name
    override val thumbnailUrl: String?
        get() = artist.thumbnailUrl
}
