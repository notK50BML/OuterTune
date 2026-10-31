package com.zionhuang.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What counts as another version of an album on its page - see YouTube.otherVersionsOf. */
class OtherVersionsTitleTest {
    @Test
    fun `editions of one album share a base title`() {
        val base = YouTube.baseAlbumTitle("1989")
        assertEquals(base, YouTube.baseAlbumTitle("1989 (Taylor's Version)"))
        assertEquals(base, YouTube.baseAlbumTitle("1989 [Deluxe Edition]"))
        assertEquals(base, YouTube.baseAlbumTitle("1989 - Live"))
        assertEquals(YouTube.baseAlbumTitle("Midnights"), YouTube.baseAlbumTitle("Midnights (3am Edition)"))
    }

    @Test
    fun `a different album does not`() {
        assertNotEquals(YouTube.baseAlbumTitle("1989"), YouTube.baseAlbumTitle("Red"))
        assertNotEquals(YouTube.baseAlbumTitle("Folklore"), YouTube.baseAlbumTitle("Evermore"))
    }
}
