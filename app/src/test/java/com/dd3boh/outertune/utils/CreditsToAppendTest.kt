/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which credits a song that already has some may still gain from a later sync - see
 * ArtistCreditEnricher.creditsToAppend and DatabaseDao.insert(MediaMetadata).
 *
 * Both directions matter. Refusing everything left a song first seen in a sparse source short of
 * its guests for good; accepting everything put back the duplicates the enricher and the name
 * refresher had just removed.
 */
class CreditsToAppendTest {

    private fun stored(id: String, name: String) = ArtistEntity(id = id, name = name)
    private fun incoming(id: String?, name: String) = MediaMetadata.Artist(id = id, name = name)

    private fun append(existing: List<ArtistEntity>, vararg incoming: MediaMetadata.Artist) =
        ArtistCreditEnricher.creditsToAppend(existing, incoming.toList()).map { it.id to it.name }

    @Test
    fun `a richer source adds the guest a sparse one left out`() {
        val song = listOf(stored("UCa", "A"))
        assertEquals(listOf("UCb" to "B"), append(song, incoming("UCa", "A"), incoming("UCb", "B")))
        // The other spelling of the lead's channel id is still the lead.
        assertEquals(listOf("UCb" to "B"), append(song, incoming("MPLAUCa", "A"), incoming("UCb", "B")))
    }

    @Test
    fun `a source naming an artist the song was moved off adds nothing`() {
        // The refresher folded the retired (G)I-DLE channel into i-dle's current one. An album page
        // still crediting the old channel does not agree with the song, so it adds nothing.
        val song = listOf(stored("UCnew", "i-dle"))
        assertEquals(emptyList<Any>(), append(song, incoming("UCold", "(G)I-DLE")))
        // Not even with a bare-text "i-dle" alongside: that is no channel, so it proves nothing.
        assertEquals(emptyList<Any>(), append(song, incoming("UCold", "(G)I-DLE"), incoming(null, "i-dle")))
    }

    @Test
    fun `a raw combined credit is never added beside its split halves`() {
        val song = listOf(stored("UCx", "X"), stored("UCy", "Y"))
        assertEquals(emptyList<Any>(), append(song, incoming(null, "X & Y")))
        assertEquals(emptyList<Any>(), append(song, incoming("UCx", "X"), incoming("UCy", "Y"), incoming(null, "X & Y")))
    }

    @Test
    fun `the same artist under a Topic channel or another case is not added twice`() {
        val song = listOf(stored("UCa", "A"))
        assertEquals(emptyList<Any>(), append(song, incoming("UCa", "A"), incoming("UCtopic", "A - Topic")))
        assertEquals(
            listOf("UCb" to "B"),
            append(song, incoming("UCa", "A"), incoming("UCb", "B"), incoming("UCb2", "b")),
        )
    }
}
