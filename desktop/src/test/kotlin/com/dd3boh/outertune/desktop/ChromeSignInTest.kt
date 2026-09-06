/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a session out of a DevTools Protocol reply.
 *
 * The rest of the flow needs a browser and a person, and cannot honestly be tested here. This part
 * can: a `Network.getAllCookies` reply is just JSON, and every decision about what counts as a
 * signed-in session is made in this function.
 *
 * The messages below are shaped as Chrome actually sends them - `{"id":n,"result":{"cookies":[…]}}`
 * with each cookie carrying name, value, domain and more.
 */
class ChromeSignInTest {

    private fun cookie(name: String, value: String, domain: String) =
        """{"name":"$name","value":"$value","domain":"$domain","path":"/","secure":true,"httpOnly":true}"""

    private fun reply(vararg cookies: String) =
        """{"id":1,"result":{"cookies":[${cookies.joinToString(",")}]}}"""

    @Test
    fun `a signed-in reply yields a session`() {
        val message = reply(
            cookie("SAPISID", "abc123", ".youtube.com"),
            cookie("SID", "sid-value", ".youtube.com"),
            cookie("HSID", "hsid-value", ".youtube.com"),
        )
        val session = ChromeSignIn.extractSession(message)
        assertEquals("SAPISID=abc123; SID=sid-value; HSID=hsid-value", session)
    }

    @Test
    fun `a browser that is not signed in yet yields nothing`() {
        // This is the ordinary case, not an error: it is what every poll returns while the user is
        // still typing their password. Returning anything here would end the wait early with a
        // credential that does not work.
        val message = reply(
            cookie("VISITOR_INFO1_LIVE", "anon", ".youtube.com"),
            cookie("PREF", "f1=x", ".youtube.com"),
            cookie("NID", "tracking", ".google.com"),
        )
        assertNull(ChromeSignIn.extractSession(message))
    }

    @Test
    fun `an empty jar yields nothing`() {
        assertNull(ChromeSignIn.extractSession(reply()))
    }

    @Test
    fun `cookies for other sites are ignored`() {
        // The profile is this app's own, so in practice there is little else in it - but the browser
        // is a real one the user has been typing into, and taking more than signing in requires
        // would not be defensible whatever the odds.
        val message = reply(
            cookie("SAPISID", "wanted", ".youtube.com"),
            cookie("SAPISID", "definitely-not", ".bank.example"),
            cookie("SID", "also-not", ".example.com"),
        )
        val session = ChromeSignIn.extractSession(message)!!
        assertTrue(session.contains("wanted"))
        assertTrue(!session.contains("definitely-not"))
        assertTrue(!session.contains("also-not"))
    }

    @Test
    fun `unrelated google cookies are left behind`() {
        val message = reply(
            cookie("SAPISID", "wanted", ".youtube.com"),
            cookie("NID", "tracking", ".google.com"),
            cookie("1P_JAR", "whatever", ".google.com"),
        )
        val session = ChromeSignIn.extractSession(message)!!
        assertTrue(!session.contains("NID"))
        assertTrue(!session.contains("1P_JAR"))
    }

    @Test
    fun `the youtube copy of a name beats the google one`() {
        // Both exist after a real sign-in and they are not always equal. The request about to be
        // signed goes to music.youtube.com.
        val message = reply(
            cookie("SAPISID", "google-copy", ".google.com"),
            cookie("SAPISID", "youtube-copy", ".youtube.com"),
        )
        val session = ChromeSignIn.extractSession(message)!!
        assertTrue("took the google.com copy", session.contains("youtube-copy"))
        assertTrue(!session.contains("google-copy"))
    }

    @Test
    fun `an empty value does not count as being signed in`() {
        // A cleared cookie is sent with an empty value. Accepting it would end the wait with a
        // credential that looks complete and fails on every request.
        assertNull(ChromeSignIn.extractSession(reply(cookie("SAPISID", "", ".youtube.com"))))
    }

    @Test
    fun `a reply to some other command is not mistaken for cookies`() {
        // The socket carries every reply and every event. Only one of them has a cookie jar in it.
        assertNull(ChromeSignIn.extractSession("""{"id":1,"result":{"frameId":"abc"}}"""))
        assertNull(ChromeSignIn.extractSession("""{"method":"Network.responseReceived","params":{}}"""))
    }

    @Test
    fun `an error reply is not mistaken for an empty jar`() {
        assertNull(
            ChromeSignIn.extractSession("""{"id":1,"error":{"code":-32601,"message":"not found"}}""")
        )
    }

    @Test
    fun `malformed json is refused rather than thrown`() {
        // The far end is a browser, not a contract. A parse failure here must cost one poll, not the
        // whole sign-in.
        assertNull(ChromeSignIn.extractSession("not json at all"))
        assertNull(ChromeSignIn.extractSession(""))
        assertNull(ChromeSignIn.extractSession("""{"result":{"cookies":"not an array"}}"""))
    }

    @Test
    fun `a cookie missing fields does not break the rest`() {
        val message = """{"id":1,"result":{"cookies":[{"name":"SAPISID"},""" +
            """{"value":"orphan"},${cookie("SAPISID", "good", ".youtube.com")}]}}"""
        assertEquals("SAPISID=good", ChromeSignIn.extractSession(message))
    }

    @Test
    fun `the result is in the format the API expects`() {
        // Semicolon-space separated name=value - a Cookie header, which is what parseCookieString
        // splits on at the other end.
        val message = reply(
            cookie("SAPISID", "a", ".youtube.com"),
            cookie("SID", "b", ".youtube.com"),
        )
        assertEquals("SAPISID=a; SID=b", ChromeSignIn.extractSession(message))
    }
}
