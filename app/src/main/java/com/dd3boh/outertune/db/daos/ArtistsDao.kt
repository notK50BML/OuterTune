package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistThumbnail
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.TOPIC_SUFFIX
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.ui.utils.resize
import com.zionhuang.innertube.pages.ArtistPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

/*
 * Logic related to artists entities and their mapping
 */

@Dao
interface ArtistsDao {

    // region Gets
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND song.inLibrary IS NOT NULL
        WHERE artist.id = :id
        GROUP BY artist.id
    """)
    fun artist(id: String): Flow<Artist?>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artistById(id: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE name = :name")
    fun artistByName(name: String): ArtistEntity?

    /**
     * Case-insensitive counterpart to [artistByName]. Callers pass an already-Topic-stripped name
     * (see [com.dd3boh.outertune.db.stripTopicSuffix]) so an artist credited under YouTube's
     * auto-generated "- Topic" channel - its own distinct channel id, separate from the artist's
     * real one if they have one - resolves to whatever ArtistEntity is already known for that
     * artist instead of minting a second, differently-named one. Used by the general song/album
     * artist sync in [com.dd3boh.outertune.db.DatabaseDao] and
     * [com.dd3boh.outertune.db.daos.AlbumsDao], and by
     * [com.dd3boh.outertune.utils.ArtistCreditEnricher]'s search-based resolution.
     */
    @Query("SELECT * FROM artist WHERE name = :name COLLATE NOCASE LIMIT 1")
    fun artistByNameIgnoreCase(name: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE isLocal = 1 AND name LIKE '%' || :name || '%'")
    fun localArtistsByNameFuzzy(name: String): List<ArtistEntity>

    /**
     * A different artist row with the same name that actually holds library songs.
     *
     * For the case where a credit and a page disagree about which channel an artist is. YouTube
     * gives the same real-world artist more than one channel - most often an auto-generated
     * "- Topic" one alongside their real one - and the library keeps whichever id it happened to
     * see first. Opening the page for the other one then shows the right name and nothing else,
     * because the songs are mapped to the id that was seen first.
     *
     * `HAVING COUNT(song.id) > 0` is what makes this safe to act on: it will not return an artist
     * who has nothing either, so the worst case is that nothing is found and the page is left
     * exactly as it was. Ordered by song count so the row with the strongest claim wins when a name
     * really is shared by more than one artist.
     */
    @Query("""
        SELECT artist.* FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND song.inLibrary IS NOT NULL
        WHERE artist.name = :name COLLATE NOCASE AND artist.id <> :excludingId
        GROUP BY artist.id
        HAVING COUNT(song.id) > 0
        ORDER BY COUNT(song.id) DESC
        LIMIT 1
    """)
    fun artistWithSongsByNameIgnoreCase(name: String, excludingId: String): ArtistEntity?

    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.name LIKE '%' || :query || '%' AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        GROUP BY artist.id
        HAVING songCount > 0
        ORDER BY artist.bookmarkedAt ASC
        LIMIT :previewSize
    """)
    fun searchArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>

    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.name LIKE '%' || :query || '%' AND song.inLibrary IS NOT NULL AND song.isLocal
        GROUP BY artist.id
        HAVING songCount > 0
        LIMIT :previewSize
    """)
    fun searchLocalArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>


    @Transaction
    @Query("""
        SELECT song.* 
        FROM song_artist_map JOIN song ON song_artist_map.songId = song.id 
        WHERE song_artist_map.artistId IN (SELECT id FROM artist WHERE name LIKE '%' || :query || '%') AND song.inLibrary IS NOT NULL 
        LIMIT :previewSize
    """)
    fun searchArtistSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Query("SELECT * FROM artist WHERE name LIKE '%' || :query || '%' LIMIT :previewSize")
    fun artistsByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal != 1")
    fun allRemoteArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal = 1")
    fun allLocalArtists(): List<ArtistEntity>

    /**
     * Artists ranked over the same window, by the same measure, as [mostPlayedSongs].
     *
     * This used to read the playCount table, which only records a total per calendar month. That
     * made the artist chart disagree with the song chart in three ways at once: its window was
     * rounded out to whole months (a "1 month" period actually covered five and a half weeks),
     * it ranked by number of plays while songs ranked by time listened, and it counted only songs
     * in the library - which on a real collection silently discarded about a quarter of all plays,
     * so an artist could be missing from the chart while their track sat at number one just above
     * it. Reading from event fixes all three, because it is the same source the song chart uses.
     */
    @Query("""
        SELECT
            artist.*,
            COUNT(DISTINCT sam.songId) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            JOIN (SELECT sam.artistId AS rankedArtistId,
                         SUM(event.playTime) AS totalPlayTime,
                         COUNT(*)            AS totalPlays
                  FROM event
                      JOIN song_artist_map sam ON sam.songId = event.songId
                  WHERE event.timestamp > :fromTimeStamp
                  GROUP BY sam.artistId) AS ranked
                 ON ranked.rankedArtistId = artist.id
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        GROUP BY artist.id
        ORDER BY (CASE WHEN :byPlayTime THEN ranked.totalPlayTime ELSE ranked.totalPlays END) DESC
        LIMIT :limit
    """)
    fun mostPlayedArtists(
        fromTimeStamp: Long,
        limit: Int = 6,
        byPlayTime: Boolean = true,
    ): Flow<List<Artist>>

    @RawQuery(observedEntities = [ArtistEntity::class])
    fun _getArtists(query: SupportSQLiteQuery): Flow<List<Artist>>

    fun artists(filter: ArtistFilter, sortType: ArtistSortType, descending: Boolean, localOnly: Boolean? = null): Flow<List<Artist>> {
        val orderBy = when (sortType) {
            ArtistSortType.CREATE_DATE -> "artist.rowId ASC"
            ArtistSortType.NAME -> "artist.name COLLATE NOCASE ASC"
            ArtistSortType.SONG_COUNT -> "songCount ASC"
        }

        val where = when (filter) {
            ArtistFilter.DOWNLOADED -> "song.dateDownload IS NOT NULL"
            ArtistFilter.LIBRARY -> "song.inLibrary IS NOT NULL"
            ArtistFilter.LIKED -> "artist.bookmarkedAt IS NOT NULL"
        } + if (localOnly == null) {
            ""
        } else if (localOnly) {
            " AND artist.isLocal = 1"
        } else {
            " AND artist.isLocal = 0"
        }

        val having = when (filter) {
            ArtistFilter.DOWNLOADED -> "AND downloadCount > 0"
            else -> ""
        }

        val query = SimpleSQLiteQuery("""
            SELECT 
                artist.*,
                COUNT(song.id) AS songCount,
                SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM artist
                LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
                LEFT JOIN song ON sam.songId = song.id
            WHERE $where
            GROUP BY artist.id
            HAVING songCount >= 0 $having
            ORDER BY $orderBy
        """)

        return _getArtists(query).map { artists ->
            artists
                .filter { it.artist.isYouTubeArtist || it.artist.isLocal } // TODO: add ui to filter by local or remote or something idk
                .reversed(descending)
        }
    }

    fun artistsInLibraryAsc() = artists(ArtistFilter.LIBRARY, ArtistSortType.CREATE_DATE, false)
    fun artistsBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false)
    fun artistsLocalBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false, true)

    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.isLocal = 1
        GROUP BY artist.id
        ORDER BY artist.name ASC
    """)
    fun localArtistsByName(): List<Artist>

    /**
     * Representative artwork path for each local artist, preferring a local album cover and falling
     * back to a local song's embedded artwork. MIN() keeps the choice stable across rescans.
     */
    @Query("""
        SELECT sam.artistId AS artistId,
               COALESCE(
                   MIN(CASE WHEN album.isLocal = 1 THEN album.thumbnailUrl END),
                   MIN(CASE WHEN song.isLocal = 1 THEN song.thumbnailUrl END)
               ) AS thumbnailUrl
        FROM song_artist_map sam
            JOIN song ON sam.songId = song.id
            LEFT JOIN album ON song.albumId = album.id
        WHERE song.isLocal = 1
        GROUP BY sam.artistId
    """)
    fun localArtistThumbnails(): Flow<List<LocalArtistThumbnail>>
    // endregion

    // region Artist Songs Sort
    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY inLibrary")
    fun artistSongsByCreateDateAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun artistSongsByNameAsc(artistId: String): Flow<List<Song>>

    fun artistSongs(artistId: String, sortType: ArtistSongSortType, descending: Boolean) =
        when (sortType) {
            ArtistSongSortType.CREATE_DATE -> artistSongsByCreateDateAsc(artistId)
            ArtistSongSortType.NAME -> artistSongsByNameAsc(artistId)
        }.map { it.reversed(descending) }
    // endregion
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongArtistMap)
    // endregion

    // region Updates
    @Update
    fun update(artist: ArtistEntity)

    /**
     * Inserts [artist], or - if an entity already exists under its id - heals a stale display
     * name left over from before the "- Topic" dedup existed. insert()'s OnConflictStrategy.IGNORE
     * would otherwise silently no-op on the id conflict and leave that stale name in place forever:
     * the general song/album artist sync in [com.dd3boh.outertune.db.DatabaseDao] and
     * [com.dd3boh.outertune.db.daos.AlbumsDao] look up an existing entity by its already-stripped
     * name via [artistByNameIgnoreCase], which can only find it if that stored name was already
     * clean - an entity still carrying the raw "- Topic" suffix from before this dedup existed
     * needs an actual update to fix, not another insert.
     */
    fun insertOrHealArtist(artist: ArtistEntity): ArtistEntity {
        val existing = artistById(artist.id)
        if (existing == null) {
            insert(artist)
            return artist
        }
        if (existing.name != artist.name) {
            val healed = existing.copy(name = artist.name)
            update(healed)
            return healed
        }
        return existing
    }

    @Transaction
    fun update(artist: ArtistEntity, artistPage: ArtistPage) {
        // See DatabaseDao.insert's identical stripTopicSuffix() call for why an artist name is
        // never stored as YTM returned it verbatim.
        val freshName = artistPage.artist.title.stripTopicSuffix()
        // A channel titled exactly "- Topic" names nobody, and the suffix surviving the strip is
        // how that shows: stripping tidies a name, it does not delete one, so a title with nothing
        // in front of the suffix comes back unchanged. Keeping the stored name in that case matters
        // here as well as at the caller, because this is the write - a refresh that only meant to
        // update the picture would otherwise take the name down with it.
        val name = if (freshName.isNotBlank() && !TOPIC_SUFFIX.containsMatchIn(freshName)) {
            freshName
        } else {
            artist.name
        }
        update(
            artist.copy(
                name = name,
                thumbnailUrl = artistPage.artist.thumbnail?.resize(544, 544),
                lastUpdateTime = LocalDateTime.now()
            )
        )
    }

    @Transaction
    @Query("UPDATE song_artist_map SET artistId = :newId WHERE artistId = :oldId")
    fun updateSongArtistMap(oldId: String, newId: String)

    /** Artist rows stored under the MPLA spelling of a channel id - see normalizeArtistId. */
    @Query("SELECT * FROM artist WHERE id LIKE 'MPLAUC%'")
    fun artistsWithPrefixedIds(): List<ArtistEntity>

    /**
     * Drops the credits that would collide before [updateSongArtistMap] moves the rest.
     *
     * song_artist_map is keyed on (songId, artistId), so a song credited under both spellings of
     * one channel cannot simply have the prefixed row repointed onto the bare one - that is the
     * duplicate key. Deleting the prefixed row for exactly those songs leaves the update with only
     * the rows that can move.
     */
    @Query("""
        DELETE FROM song_artist_map
        WHERE artistId = :oldId
          AND songId IN (SELECT songId FROM song_artist_map WHERE artistId = :newId)
    """)
    fun deleteCollidingSongArtistMaps(oldId: String, newId: String)
    // endregion

    // region Deletes
    @Delete
    fun delete(artist: ArtistEntity)

   @Query("""
        DELETE FROM Artist
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_artist_map
            WHERE song_artist_map.artistId = :artistId
        )
        AND id = :artistId
    """)
    fun safeDeleteArtist(artistId: String)

    /**
     * Used by ArtistCreditEnricher to replace a single combined credit (e.g. one ArtistEntity
     * literally named "X & Y", with no real channel behind it) with the separate, verified
     * artists it was standing in for - the ArtistEntity itself is left alone (safeDeleteArtist
     * afterward if it's now unused by anything else).
     */
    @Query("DELETE FROM song_artist_map WHERE songId = :songId AND artistId = :artistId")
    fun deleteSongArtistMap(songId: String, artistId: String)

    @Transaction
    @Query("DELETE FROM artist WHERE isLocal = 1")
    fun nukeLocalArtists()
    // endregion
}