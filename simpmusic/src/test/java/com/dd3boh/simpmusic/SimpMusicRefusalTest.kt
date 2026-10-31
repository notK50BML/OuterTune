package com.dd3boh.simpmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SimpMusicLyrics.refusalReason] recognises the API turning this client away, and nothing else.
 */
class SimpMusicRefusalTest {

    /** The body the live API returns, verbatim. */
    private val refusal =
        """{"success": false, "error": {"error": true, "code": 403, "reason": "This API is only available to SimpMusic clients"}}"""

    @Test
    fun recognisesTheApisOwnRefusal() {
        assertEquals("This API is only available to SimpMusic clients", SimpMusicLyrics.refusalReason(403, refusal))
    }

    @Test
    fun cloudflareChallengeIsNotARefusal() {
        // Same status, but an HTML page from the CDN in front of the API - possibly transient.
        assertNull(SimpMusicLyrics.refusalReason(403, "<!DOCTYPE html><html><title>Just a moment...</title></html>"))
    }

    @Test
    fun otherStatusesAreNotARefusal() {
        assertNull(SimpMusicLyrics.refusalReason(500, refusal))
        assertNull(SimpMusicLyrics.refusalReason(429, refusal))
    }
}
