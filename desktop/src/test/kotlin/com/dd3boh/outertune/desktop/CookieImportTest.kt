/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cookie file parser.
 *
 * Worth testing carefully because every failure here looks the same from outside - "signing in did
 * not work" - and the file is the user's actual credential, so a second attempt costs them another
 * round trip through a browser extension.
 */
class CookieImportTest {

    private fun line(domain: String, name: String, value: String, httpOnly: Boolean = false) =
        (if (httpOnly) "#HttpOnly_" else "") +
            "$domain\tTRUE\t/\tTRUE\t1900000000\t$name\t$value"

    private fun file(vararg lines: String) =
        ("# Netscape HTTP Cookie File\n# This is a generated file!\n" + lines.joinToString("\n"))

    @Test
    fun `a normal export yields a session`() {
        val text = file(
            line(".youtube.com", "SAPISID", "abc123"),
            line(".youtube.com", "SID", "sid-value"),
            line(".youtube.com", "HSID", "hsid-value"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("SAPISID=abc123"))
        assertTrue(result.cookie.contains("SID=sid-value"))
        assertEquals(listOf("SAPISID", "SID", "HSID"), result.names)
    }

    @Test
    fun `HttpOnly lines are cookies, not comments`() {
        // The single most likely way to get this wrong. Google's session cookies are all HttpOnly,
        // and exporters mark them with a leading #HttpOnly_ - so treating # as a comment throws away
        // exactly the lines that matter and leaves a file that appears to have parsed fine.
        val text = file(
            line(".youtube.com", "SAPISID", "abc123", httpOnly = true),
            line(".youtube.com", "__Secure-3PSID", "secure", httpOnly = true),
        )
        val result = CookieImport.fromText(text)
        assertTrue("HttpOnly cookies were discarded", result is CookieImport.Result.Session)
        assertTrue((result as CookieImport.Result.Session).cookie.contains("SAPISID=abc123"))
    }

    @Test
    fun `real comments are still ignored`() {
        val text = "# Netscape HTTP Cookie File\n# comment\n\n" +
            line(".youtube.com", "SAPISID", "abc123")
        assertTrue(CookieImport.fromText(text) is CookieImport.Result.Session)
    }

    @Test
    fun `cookies for other sites are left out`() {
        // An export is usually the whole browser. Sending someone's bank session to YouTube would be
        // both useless and a genuine harm.
        val text = file(
            line(".youtube.com", "SAPISID", "abc123"),
            line(".example.com", "SID", "not-google"),
            line(".bank.example", "SAPISID", "definitely-not"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("abc123"))
        assertTrue("a foreign cookie was included", !result.cookie.contains("not-google"))
        assertTrue(!result.cookie.contains("definitely-not"))
    }

    @Test
    fun `unrelated google cookies are not dragged along`() {
        val text = file(
            line(".youtube.com", "SAPISID", "abc123"),
            line(".google.com", "NID", "tracking"),
            line(".google.com", "1P_JAR", "whatever"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertTrue(!result.cookie.contains("NID"))
        assertTrue(!result.cookie.contains("1P_JAR"))
    }

    @Test
    fun `a signed-out export is reported as such, not as a broken file`() {
        // Different problem, different fix: the user needs to sign in, not to export again. Saying
        // "unreadable" would send them to correct the wrong thing.
        val text = file(
            line(".youtube.com", "VISITOR_INFO1_LIVE", "anon"),
            line(".youtube.com", "PREF", "f1=x"),
        )
        assertEquals(CookieImport.Result.NoSession, CookieImport.fromText(text))
    }

    @Test
    fun `the wrong kind of file is reported as unreadable`() {
        val result = CookieImport.fromText("{ \"not\": \"a cookie file\" }")
        assertTrue(result is CookieImport.Result.Unreadable)
    }

    @Test
    fun `an empty file is unreadable rather than a signed-out session`() {
        assertTrue(CookieImport.fromText("") is CookieImport.Result.Unreadable)
        assertTrue(CookieImport.fromText("# only comments\n") is CookieImport.Result.Unreadable)
    }

    @Test
    fun `a truncated line is skipped rather than misread`() {
        val text = file(
            ".youtube.com\tTRUE\t/\tTRUE",
            line(".youtube.com", "SAPISID", "abc123"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("SAPISID=abc123"))
    }

    @Test
    fun `an empty value does not count as having the cookie`() {
        // A cleared cookie is written out with an empty value. Accepting it produces a cookie string
        // that looks complete and fails on every request.
        val text = file(line(".youtube.com", "SAPISID", ""))
        assertEquals(CookieImport.Result.NoSession, CookieImport.fromText(text))
    }

    @Test
    fun `the first occurrence of a name wins`() {
        // An export can carry the same name for several domains. Overwriting would swap in whichever
        // came last in the file, which is not a choice anybody made.
        val text = file(
            line(".youtube.com", "SAPISID", "the-right-one"),
            line(".google.com", "SAPISID", "the-other-one"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("the-right-one"))
        assertTrue(!result.cookie.contains("the-other-one"))
    }

    @Test
    fun `the result is in the format the API expects`() {
        // Semicolon-space separated name=value, which is what a Cookie header is and what
        // parseCookieString on the other side splits on.
        val text = file(
            line(".youtube.com", "SAPISID", "a"),
            line(".youtube.com", "SID", "b"),
        )
        val result = CookieImport.fromText(text) as CookieImport.Result.Session
        assertEquals("SAPISID=a; SID=b", result.cookie)
    }

    @Test
    fun `windows line endings are handled`() {
        // The file comes from a browser download on Windows as often as not.
        val text = file(line(".youtube.com", "SAPISID", "abc123")).replace("\n", "\r\n")
        val result = CookieImport.fromText(text)
        assertTrue("CRLF broke the parse", result is CookieImport.Result.Session)
        assertEquals("SAPISID=abc123", (result as CookieImport.Result.Session).cookie)
    }
}
