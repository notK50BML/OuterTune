package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One song's own lyrics timing offset, in milliseconds - positive shows each line later. A song
 * with no row has none.
 *
 * A table of its own rather than a column on [LyricsEntity], because that row is replaced whole
 * every time lyrics are fetched, edited or picked from a search - an offset kept there would be
 * reset to zero by each of them, including the refetch someone tries before reaching for the offset.
 * And no foreign key to the song: the offset is set on whatever is playing, which need not be in the
 * library yet.
 */
@Entity(tableName = "lyrics_offset")
data class LyricsOffsetEntity(
    @PrimaryKey val id: String,
    val offsetMs: Int,
)
