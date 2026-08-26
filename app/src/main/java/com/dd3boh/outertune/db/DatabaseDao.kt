package com.dd3boh.outertune.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.db.daos.AlbumsDao
import com.dd3boh.outertune.db.daos.ArtistsDao
import com.dd3boh.outertune.db.daos.PlaylistsDao
import com.dd3boh.outertune.db.daos.QueueDao
import com.dd3boh.outertune.db.daos.SongsDao
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.EventWithSong
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.db.entities.RecentActivityEntity
import com.dd3boh.outertune.db.entities.RecentActivityType
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongGenreMap
import com.dd3boh.outertune.extensions.toSQLiteQuery
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.coroutines.flow.Flow

@Dao
interface DatabaseDao : SongsDao, AlbumsDao, ArtistsDao, PlaylistsDao, QueueDao {

    @Transaction
    @Query("""
        SELECT song.*
        FROM (SELECT *, COUNT(1) AS referredCount
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        WHERE songId IN (SELECT songId
                         FROM (SELECT songId
                               FROM event
                               ORDER BY ROWID DESC
                               LIMIT 5)
                         UNION
                         SELECT songId
                         FROM (SELECT songId
                               FROM event
                               WHERE timestamp > :now - 86400000 * 7
                               GROUP BY songId
                               ORDER BY SUM(playTime) DESC
                               LIMIT 5)
                         UNION
                         SELECT id
                         FROM (SELECT id
                               FROM song
                               LIMIT 10))
        ORDER BY referredCount DESC
        LIMIT 100
    """)
    fun quickPicks(now: Long = System.currentTimeMillis()): Flow<List<Song>>

    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    fun clearListenHistory()

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    fun clearSearchHistory()

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    fun hasRelatedSongs(songId: String): Boolean

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT *
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN
             song
             ON song.id = map.relatedSongId
        WHERE songId = :songId
        """
    )
    fun relatedSongs(songId: String): List<Song>

    @Query("""
        SELECT * FROM genre
        WHERE genre.isLocal = 1
        ORDER BY genre.title ASC
    LIMIT :previewSize""")
    fun allLocalGenresByName(previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Query("SELECT * FROM genre WHERE id = :id")
    fun genreById(id: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title = :name")
    fun genreByName(name: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE isLocal = 1 AND title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun localGenreByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Transaction
    @Query("UPDATE song_genre_map SET genreId = :newId WHERE genreId = :oldId")
    fun updateSongGenreMap(oldId: String, newId: String)

    @Query(
        """
        DELETE FROM genre
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_genre_map
            WHERE song_genre_map.genreId = :genreId
        )
        AND id = :genreId
    """
    )
    fun safeDeleteGenre(genreId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongGenreMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchHistory: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: Event)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: RelatedSongMap)

    @Transaction
    fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {
        val isNewSong = insert(mediaMetadata.toSongEntity().let(block)) != -1L
        // Artist/genre mappings run even for a song that already existed: one first cached from a
        // sparse source (search, queue, related) can be missing artists a richer source - e.g. the
        // very album this call came from - actually credits it with, and previously this whole
        // function returned immediately on the SongEntity insert's OnConflictStrategy.IGNORE,
        // silently discarding that richer artist list. The inserts below are already
        // OnConflictStrategy.IGNORE on [songId, artistId]/[songId, genreId], so re-running this for
        // an existing song only ever adds a mapping that was missing, never duplicates or removes
        // one that's already there.
        mediaMetadata.artists.forEachIndexed { index, artist ->
            // A song's artist can come in credited under YouTube's auto-generated "- Topic"
            // channel - its own distinct channel id, separate from the artist's real one if they
            // have one. Matching by the stripped display name first (rather than trusting
            // artist.id outright) means a "- Topic" credit for an artist already known under their
            // real channel merges into that existing entity instead of creating a second,
            // differently-named one for the same real-world artist - the same clean-name dedup
            // ArtistCreditEnricher and the one-time migrations already use, just applied
            // prospectively for every song synced from here on rather than as a one-time fixup.
            // insertOrHealArtist additionally corrects a stale "- Topic" name already sitting
            // under this id from before this dedup existed, which a plain insert (and even this
            // same lookup) could never fix on their own - see its own doc.
            val cleanName = artist.name.stripTopicSuffix()
            val artistId = artistByNameIgnoreCase(cleanName)?.id ?: artist.id ?: ArtistEntity.generateArtistId()
            insertOrHealArtist(
                ArtistEntity(
                    id = artistId,
                    name = cleanName,
                    isLocal = artist.isLocal
                )
            )
            insert(
                SongArtistMap(
                    songId = mediaMetadata.id,
                    artistId = artistId,
                    position = index
                )
            )
        }
        mediaMetadata.genre?.forEachIndexed { index, genre ->
            val genreId = genreByName(genre.title)?.id ?: GenreEntity.generateGenreId()
            insert( // TODO: use upsert???
                GenreEntity(
                    id = genreId,
                    title = genre.title,
                    isLocal = genre.isLocal
                )
            )
            insert(
                SongGenreMap(
                    songId = mediaMetadata.id,
                    genreId = genreId,
                    index = index
                )
            )
        }

        // Unlike the mappings above, this isn't idempotent - it adds mediaMetadata.duration to the
        // album's running total every time it runs, so it must stay gated to the song's actual
        // first insert or a re-view of an already-known song would keep inflating that total.
        if (!isNewSong) return
        mediaMetadata.album?.let {
            val album = albumsByName(it.title)
            val albumId = album?.id ?: GenreEntity.generateGenreId()
            upsert(
                AlbumEntity(
                    id = albumId,
                    title = it.title,
                    thumbnailUrl = album?.thumbnailUrl?: mediaMetadata.thumbnailUrl,
                    songCount = (album?.songCount ?: 0).coerceAtLeast(1),
                    duration = (album?.duration ?: 0) + mediaMetadata.duration,
                    isLocal = it.isLocal
                )
            )
            insert(
                SongAlbumMap(
                    songId = mediaMetadata.id,
                    albumId = albumId,
                    index = album?.songCount ?: 0
                )
            )
        }
    }

    @Transaction
    fun insert(albumPage: AlbumPage) {
        if (insert(AlbumEntity(
                id = albumPage.album.browseId,
                playlistId = albumPage.album.playlistId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 }
            )) == -1L
        ) return
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists
            ?.map { artist ->
                // See the identical dedup in insert(mediaMetadata, ...)'s own doc for why this
                // doesn't just trust artist.id/artist.name as given, and insertOrHealArtist's own
                // doc for why a plain insert isn't enough on its own either.
                val cleanName = artist.name.stripTopicSuffix()
                ArtistEntity(
                    id = artistByNameIgnoreCase(cleanName)?.id ?: artist.id ?: ArtistEntity.generateArtistId(),
                    name = cleanName
                )
            }
            ?.map(::insertOrHealArtist)
            ?.mapIndexed { index, artist ->
                AlbumArtistMap(
                    albumId = albumPage.album.browseId,
                    artistId = artist.id,
                    order = index
                )
            }
            ?.forEach(::insert)
    }

    @Transaction
    fun update(album: AlbumEntity, albumPage: AlbumPage) {
        update(
            album.copy(
                id = albumPage.album.browseId,
                playlistId = albumPage.album.playlistId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 }
            )
        )
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists
            ?.map { artist ->
                // See the identical dedup in insert(mediaMetadata, ...)'s own doc for why this
                // doesn't just trust artist.id/artist.name as given, and insertOrHealArtist's own
                // doc for why a plain insert isn't enough on its own either.
                val cleanName = artist.name.stripTopicSuffix()
                ArtistEntity(
                    id = artistByNameIgnoreCase(cleanName)?.id ?: artist.id ?: ArtistEntity.generateArtistId(),
                    name = cleanName
                )
            }
            ?.map(::insertOrHealArtist)
            ?.mapIndexed { index, artist ->
                AlbumArtistMap(
                    albumId = albumPage.album.browseId,
                    artistId = artist.id,
                    order = index
                )
            }
            ?.forEach(::insert)
    }

    @Upsert
    fun upsert(lyrics: LyricsEntity)

    @Upsert
    fun upsert(format: FormatEntity)

    @Delete
    fun delete(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics where id = :id")
    fun deleteLyricById(id: String)

    @Delete
    fun delete(searchHistory: SearchHistory)

    @Delete
    fun delete(event: Event)


    /**
     * WARNING: This removes all queue song data and re-adds the queue. Did you mean to use updateQueue()?
     */
    @Transaction
    fun saveQueue(mq: MultiQueueObject) {
        if (mq.queue.isEmpty()) {
            return
        }

        insert(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )

        deleteAllQueueSongs(mq.id)
        // insert songs

        // why does kotlin not have for i loop???
        var i = 0
        while (i < mq.getSize()) {
            insert(mq.queue[i]) // make sure song exists
            insert(
                QueueSongMap(
                    queueId = mq.id,
                    songId = mq.queue[i].id,
                    index = i.toLong(),
                    shuffledIndex = mq.queue[i].shuffleIndex.toLong()
                )
            )
            i ++
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: RecentActivityEntity)

    @Delete
    fun delete(item: RecentActivityEntity)

    @Query("DELETE FROM recent_activity")
    fun clearRecentActivity()

    @Transaction
    fun insertRecentActivityItem(item: YTItem) {
        when (item) {
            is AlbumItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.browseId,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ALBUM,
                        playlistId = item.playlistId,
                        radioPlaylistId = null,
                        shufflePlaylistId = null
                    )
                )
            }

            is PlaylistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.PLAYLIST,
                        playlistId = item.id,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            is ArtistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ARTIST,
                        playlistId = item.playEndpoint?.playlistId,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            else -> {
                // do nothing
            }
        }
    }

    @Query("SELECT * FROM recent_activity ORDER BY date DESC")
    fun recentActivity(): Flow<List<RecentActivityEntity>>

    /**
     * Nukes
     */

    @Transaction
    @Query("DELETE FROM genre WHERE isLocal = 1")
    fun nukeLocalGenre()

    @Transaction
    @Query("""
DELETE FROM format 
WHERE format.id IS NOT NULL 
AND NOT EXISTS (
    SELECT 1 FROM song WHERE song.id = format.id
);
    """)
    fun nukeDanglingFormatEntities()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id IN (SELECT song.id FROM song WHERE song.isLocal)")
    fun nukeLocalLyrics()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id NOT IN (SELECT song.id FROM song)")
    fun nukeDanglingLyrics()

    @Transaction
    @Query("DELETE FROM playlist WHERE isLocal = 0")
    fun nukeRemotePlaylists()

    @Transaction
    fun nukeLocalData() {
        nukeLocalSongs()
        nukeLocalArtists()
        nukeLocalAlbums()
        nukeLocalGenre()
    }

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw("PRAGMA wal_checkpoint(FULL)".toSQLiteQuery())
    }
}
