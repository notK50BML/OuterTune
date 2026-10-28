package com.zionhuang.innertube

import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.ArtistPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artists of a row on an artist page's Songs shelf. The second artist of "A & B" used to be
 * dropped, so B's own page listed the song as A's alone.
 */
@OptIn(ExperimentalSerializationApi::class)
class ArtistPageSongArtistsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun artistRun(name: String, id: String?) = if (id == null) """{"text":"$name"}""" else """
        {"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
          "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}
    """

    private fun separator(text: String) = """{"text":"$text"}"""

    /** One Songs shelf holding a single row whose artist column is [artistRuns]. */
    private fun shelfArtists(vararg artistRuns: String): List<Pair<String, String?>> {
        val content = """
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[{"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"vid1"},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/t.jpg"}]}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${artistRuns.joinToString(",")}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"1M plays"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album",
                  "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album",
                    "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}
              ]}}]}}
        """
        val section = ArtistPage.fromSectionListRendererContent(json.decodeFromString<SectionListRenderer.Content>(content))!!
        return (section.items.single() as SongItem).artists.map { it.name to it.id }
    }

    @Test
    fun everyLinkedArtistIsKept() {
        assertEquals(
            listOf("A" to "UCa", "B" to "UCb", "C" to "UCc"),
            shelfArtists(artistRun("A", "UCa"), separator(", "), artistRun("B", "UCb"), separator(" & "), artistRun("C", "UCc")),
        )
    }

    @Test
    fun unlinkedArtistsStillDropTheirSeparators() {
        assertEquals(
            listOf("A" to null, "B" to null),
            shelfArtists(artistRun("A", null), separator(" & "), artistRun("B", null)),
        )
    }
}
