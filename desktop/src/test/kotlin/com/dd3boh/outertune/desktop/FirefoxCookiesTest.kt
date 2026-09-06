/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Reading Firefox's cookie store, against a real one built for the test.
 *
 * A real SQLite file with Firefox's actual `moz_cookies` schema, because the whole risk here is
 * getting that schema or its semantics wrong - and a mock of it would agree with whatever this code
 * happens to assume.
 */
class FirefoxCookiesTest {

    private lateinit var root: File

    /** A profile directory holding a cookie store with [cookies] as (host, name, value). */
    private fun profile(name: String, vararg cookies: Triple<String, String, String>): File {
        val dir = File(root, "Profiles/$name").apply { mkdirs() }
        val db = File(dir, "cookies.sqlite")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                // Firefox's own shape, trimmed to the columns this reads. The extra columns are
                // irrelevant to the query and their absence cannot change its result.
                st.executeUpdate(
                    """
                    CREATE TABLE moz_cookies (
                        id INTEGER PRIMARY KEY,
                        host TEXT,
                        name TEXT,
                        value TEXT,
                        path TEXT,
                        expiry INTEGER,
                        isSecure INTEGER,
                        isHttpOnly INTEGER
                    )
                    """.trimIndent()
                )
            }
            conn.prepareStatement(
                "INSERT INTO moz_cookies (host, name, value, path, expiry, isSecure, isHttpOnly) " +
                    "VALUES (?, ?, ?, '/', 1900000000, 1, 1)"
            ).use { st ->
                cookies.forEach { (host, cookieName, value) ->
                    st.setString(1, host)
                    st.setString(2, cookieName)
                    st.setString(3, value)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        return dir
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ot-ff-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a signed-in profile yields a session`() {
        profile(
            "default-release",
            Triple(".youtube.com", "SAPISID", "abc123"),
            Triple(".youtube.com", "SID", "sid-value"),
        )
        val found = FirefoxCookies.profiles(root)
        assertEquals(1, found.size)

        val result = FirefoxCookies.read(found.first()) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("SAPISID=abc123"))
        assertTrue(result.cookie.contains("SID=sid-value"))
    }

    @Test
    fun `other sites are not read`() {
        // This is somebody else's credential store. Reading beyond what signing in needs would not
        // be defensible, and the query is the only thing standing between the two.
        profile(
            "default",
            Triple(".youtube.com", "SAPISID", "wanted"),
            Triple(".bank.example", "SAPISID", "absolutely-not"),
            Triple(".example.com", "SID", "also-not"),
        )
        val result = FirefoxCookies.read(FirefoxCookies.profiles(root).first()) as CookieImport.Result.Session
        assertTrue(result.cookie.contains("wanted"))
        assertTrue(!result.cookie.contains("absolutely-not"))
        assertTrue(!result.cookie.contains("also-not"))
    }

    @Test
    fun `unrelated google cookies are left behind`() {
        profile(
            "default",
            Triple(".youtube.com", "SAPISID", "wanted"),
            Triple(".google.com", "NID", "tracking"),
        )
        val result = FirefoxCookies.read(FirefoxCookies.profiles(root).first()) as CookieImport.Result.Session
        assertTrue(!result.cookie.contains("NID"))
    }

    @Test
    fun `the youtube copy of a name wins over the google one`() {
        // Both exist and they are not always the same value. The request about to be signed goes to
        // music.youtube.com, so that is the one that has to be used.
        profile(
            "default",
            Triple(".google.com", "SAPISID", "google-copy"),
            Triple(".youtube.com", "SAPISID", "youtube-copy"),
        )
        val result = FirefoxCookies.read(FirefoxCookies.profiles(root).first()) as CookieImport.Result.Session
        assertTrue("took the google.com copy", result.cookie.contains("youtube-copy"))
        assertTrue(!result.cookie.contains("google-copy"))
    }

    @Test
    fun `a signed-out profile says so rather than reporting a broken file`() {
        // Different problem, different fix. "Unreadable" would send the user to reinstall Firefox.
        profile(
            "default",
            Triple(".youtube.com", "VISITOR_INFO1_LIVE", "anon"),
            Triple(".youtube.com", "PREF", "f1=x"),
        )
        assertEquals(
            CookieImport.Result.NoSession,
            FirefoxCookies.read(FirefoxCookies.profiles(root).first()),
        )
    }

    @Test
    fun `a profile that has never seen youtube is reported as such`() {
        profile("default", Triple(".example.com", "SID", "x"))
        val result = FirefoxCookies.read(FirefoxCookies.profiles(root).first())
        assertTrue(result is CookieImport.Result.Unreadable)
    }

    @Test
    fun `profiles are offered most recently used first`() {
        // An install leaves several profiles behind and only one is signed in. Ordering by when the
        // store was last written puts the one actually in use at the top, which is the difference
        // between one click and a guessing game.
        val old = profile("old-profile", Triple(".youtube.com", "SAPISID", "old"))
        Thread.sleep(1100)
        val recent = profile("default-release", Triple(".youtube.com", "SAPISID", "recent"))
        File(old, "cookies.sqlite").setLastModified(System.currentTimeMillis() - 86_400_000)
        File(recent, "cookies.sqlite").setLastModified(System.currentTimeMillis())

        val ordered = FirefoxCookies.profiles(root)
        assertEquals(listOf("default-release", "old-profile"), ordered.map { it.name })
    }

    @Test
    fun `no firefox at all is not an error`() {
        assertTrue(FirefoxCookies.profiles(File(root, "nothing-here")).isEmpty())
    }

    @Test
    fun `a corrupt store is reported, not thrown`() {
        val dir = File(root, "Profiles/broken").apply { mkdirs() }
        File(dir, "cookies.sqlite").writeText("this is not a database")
        val result = FirefoxCookies.read(FirefoxCookies.profiles(root).first())
        assertTrue("expected a readable failure, got $result", result is CookieImport.Result.Unreadable)
    }

    @Test
    fun `reading does not disturb the original`() {
        // The store belongs to a running Firefox. Reading it in place can block, can see a partial
        // transaction, and can leave recovery journals in someone else's profile - so it is copied.
        val dir = profile("default", Triple(".youtube.com", "SAPISID", "abc"))
        val before = File(dir, "cookies.sqlite").readBytes()

        FirefoxCookies.read(FirefoxCookies.profiles(root).first())

        assertTrue("the store was modified", before.contentEquals(File(dir, "cookies.sqlite").readBytes()))
        assertTrue("a journal was left behind", File(dir, "cookies.sqlite-wal").let { !it.exists() })
        assertTrue(File(dir, "cookies.sqlite-shm").let { !it.exists() })
    }
}
