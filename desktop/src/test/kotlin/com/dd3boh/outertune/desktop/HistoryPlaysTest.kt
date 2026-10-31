package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.HistoryTally
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stats with plays from the account's history: added without counting this computer's twice. */
class HistoryPlaysTest {

    private fun item(id: String, seconds: Int = 200) = SongItem(
        id = id, title = id, artists = listOf(Artist(name = "artist", id = null)), album = null,
        duration = seconds, thumbnail = "", explicit = false, endpoint = null,
    )

    private fun local(id: String, plays: Int) = PlayedSong(StoredSong(id, id, "artist"), plays, 1L)

    @Test
    fun `a song played here takes the larger count, not the sum`() {
        val rows = withHistoryPlays(
            listOf(local("shared", 5), local("more-elsewhere", 2)),
            mapOf(
                "shared" to HistoryTally.Plays(item("shared"), 5),
                "more-elsewhere" to HistoryTally.Plays(item("more-elsewhere"), 7),
            ),
        )
        assertEquals(mapOf("shared" to 5, "more-elsewhere" to 7), rows.associate { it.song.id to it.plays })
    }

    @Test
    fun `a song played only elsewhere joins the chart`() {
        val rows = withHistoryPlays(listOf(local("here", 1)), mapOf("phone" to HistoryTally.Plays(item("phone"), 4)))
        assertEquals(mapOf("here" to 1, "phone" to 4), rows.associate { it.song.id to it.plays })
    }

    @Test
    fun `totals gain only what the history adds`() {
        val totals = PlayTotals(plays = 10, songs = 2, listenedMs = 1_000_000).withHistory(
            remote = mapOf(
                "a" to HistoryTally.Plays(item("a", seconds = 100), 6),
                "b" to HistoryTally.Plays(item("b", seconds = 60), 3),
            ),
            localPlays = mapOf("a" to 4),
        )
        // a: 2 more than played here; b: 3. The song count is the chart's - see songChart.
        assertEquals(15, totals.plays)
        assertEquals(2, totals.songs)
        assertEquals(1_000_000 + 2 * 100_000L + 3 * 60_000L, totals.listenedMs)
    }

    @Test
    fun `a song of unknown length played elsewhere adds no time`() {
        val totals = PlayTotals(plays = 0, songs = 0, listenedMs = 0)
            .withHistory(mapOf("live" to HistoryTally.Plays(item("live", seconds = -1), 2)), emptyMap())
        assertEquals(2, totals.plays)
        assertEquals(0L, totals.listenedMs)
    }

    @Test
    fun `artists gain the history plays the chart does`() {
        val here = StoredArtist("UChere", "Here")
        val other = StoredArtist("UCother", "Other")
        fun credited(id: String, artistId: String, name: String) = SongItem(
            id = id, title = id, artists = listOf(Artist(name = name, id = artistId)), album = null,
            duration = 200, thumbnail = "", explicit = false, endpoint = null,
        )
        val artists = withHistoryArtists(
            local = listOf(ArtistPlays(other, 3, 600_000), ArtistPlays(here, 1, 200_000)),
            remote = mapOf(
                // Played once here, five times in all: four more, to the credits the library has.
                "a" to HistoryTally.Plays(credited("a", "UCwrong", "Wrong"), 5),
                // Played only elsewhere: to the artist the history names, in its plain spelling.
                "x" to HistoryTally.Plays(credited("x", "MPLAUCaway", "Away"), 2),
            ),
            localPlays = mapOf("a" to 1),
            credits = mapOf("a" to listOf(here)),
        )
        assertEquals(listOf("UChere" to 5, "UCother" to 3, "UCaway" to 2), artists.map { it.artist.id to it.plays })
    }

    @Test
    fun `the history's plays bring their time, to songs and to artists`() {
        val rows = withHistoryPlays(
            listOf(PlayedSong(StoredSong("here", "here", "artist"), plays = 1, lastPlayedAt = 1L, listenedMs = 200_000)),
            mapOf(
                "here" to HistoryTally.Plays(item("here", seconds = 200), 3),
                "phone" to HistoryTally.Plays(item("phone", seconds = 100), 2),
            ),
        )
        // here: two more plays of 200 s; phone: two of 100 s.
        assertEquals(mapOf("here" to 600_000L, "phone" to 200_000L), rows.associate { it.song.id to it.listenedMs })

        val away = StoredArtist("UCaway", "Away")
        val artists = withHistoryArtists(
            local = emptyList(),
            remote = mapOf("x" to HistoryTally.Plays(
                SongItem(
                    id = "x", title = "x", artists = listOf(Artist(name = "Away", id = away.id)), album = null,
                    duration = 150, thumbnail = "", explicit = false, endpoint = null,
                ),
                2,
            )),
            localPlays = emptyMap(),
            credits = emptyMap(),
        )
        assertEquals(listOf(ArtistPlays(away, 2, 300_000)), artists)
    }

    @Test
    fun `artists rank by time listened when asked`() {
        val long = StoredArtist("UClong", "Long songs")
        val short = StoredArtist("UCshort", "Short songs")
        val local = listOf(ArtistPlays(short, 10, 1_200_000), ArtistPlays(long, 3, 1_800_000))
        fun ranked(byTime: Boolean) =
            withHistoryArtists(local, emptyMap(), emptyMap(), emptyMap(), byTime).map { it.artist.id }
        assertEquals(listOf("UCshort", "UClong"), ranked(byTime = false))
        assertEquals(listOf("UClong", "UCshort"), ranked(byTime = true))
    }
}
