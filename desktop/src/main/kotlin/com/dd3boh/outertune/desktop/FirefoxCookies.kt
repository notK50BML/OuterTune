/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File
import java.sql.DriverManager

/**
 * Reads a YouTube session out of Firefox's own cookie store.
 *
 * The same thing `yt-dlp --cookies-from-browser` does, and it turns signing in from "install an
 * extension, export a file, find the file" into one button - for Firefox users. Chrome is
 * deliberately not attempted: since Chrome 127 its cookie store is under app-bound encryption whose
 * key is tied to the Chrome process, which broke every external reader, and chasing that is a
 * commitment to keep chasing it. Firefox stores cookies in plain SQLite and has for years.
 *
 * Only ever runs when the user asks for it, and only YouTube and Google session cookies are read -
 * this is a credential store belonging to another application, and taking anything beyond what
 * signing in requires would not be defensible.
 *
 * See SIGN-IN.md for why a cookie is what is needed at all rather than a token.
 */
object FirefoxCookies {

    /**
     * Every profile with a cookie store, newest first.
     *
     * Firefox supports several profiles and most people have more than one without knowing - an
     * install creates `default` and `default-release`, and only one of them is ever signed in. So
     * they are offered in order of when they were last written, which puts the one actually in use
     * first.
     */
    fun profiles(root: File = defaultRoot()): List<Profile> {
        val profilesDir = File(root, "Profiles")
        val dirs = profilesDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val db = File(dir, "cookies.sqlite")
            if (db.isFile) Profile(dir.name, db) else null
        }.sortedByDescending { it.cookies.lastModified() }
    }

    data class Profile(val name: String, val cookies: File)

    /**
     * Pulls a session out of one profile's store.
     *
     * The file is copied before being opened. Firefox holds it open and in WAL mode while running,
     * so reading in place can block, can see a half-written transaction, and in the worst case can
     * leave recovery journals next to somebody else's database. A copy is a few hundred kilobytes
     * and removes all of that.
     */
    fun read(profile: Profile): CookieImport.Result {
        if (!profile.cookies.isFile) return CookieImport.Result.Unreadable("No cookie store in that profile.")

        val temp = File.createTempFile("ot-cookies", ".sqlite")
        return try {
            profile.cookies.copyTo(temp, overwrite = true)
            // The write-ahead log holds anything not yet checkpointed, which for a running Firefox
            // is likely to include a recently refreshed session. Copying the database without it
            // reads a state that may be hours old.
            val wal = File(profile.cookies.parentFile, "cookies.sqlite-wal")
            if (wal.isFile) {
                runCatching { wal.copyTo(File(temp.parentFile, temp.name + "-wal"), overwrite = true) }
            }
            readCopy(temp)
        } catch (e: Exception) {
            CookieImport.Result.Unreadable("Could not read Firefox's cookies: ${e.message}")
        } finally {
            temp.delete()
            File(temp.parentFile, temp.name + "-wal").delete()
            File(temp.parentFile, temp.name + "-shm").delete()
        }
    }

    private fun readCopy(db: File): CookieImport.Result {
        Class.forName("org.sqlite.JDBC")
        val found = LinkedHashMap<String, String>()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement(
                """
                SELECT name, value, host FROM moz_cookies
                WHERE host LIKE '%youtube.com' OR host LIKE '%google.com'
                """.trimIndent()
            ).use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val value = rs.getString(2) ?: continue
                        if (name !in WANTED || value.isBlank()) continue
                        val host = rs.getString(3) ?: ""
                        // A youtube.com cookie wins over a google.com one of the same name. Both
                        // exist, they are not always the same value, and it is the YouTube one the
                        // request is about to be signed with.
                        if (name !in found || host.endsWith("youtube.com")) {
                            found[name] = value
                        }
                    }
                }
            }
        }
        if (found.isEmpty()) return CookieImport.Result.Unreadable("No YouTube cookies in that profile.")
        if ("SAPISID" !in found) return CookieImport.Result.NoSession

        val cookie = found.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return CookieImport.Result.Session(cookie, found.keys.toList())
    }

    /** Where Firefox keeps its profiles on this platform. */
    fun defaultRoot(): File {
        val appData = System.getenv("APPDATA")
        if (appData != null) return File(appData, "Mozilla/Firefox")
        val home = System.getProperty("user.home")
        // Linux and macOS, in that order of likelihood for this project.
        val linux = File(home, ".mozilla/firefox")
        if (linux.isDirectory) return linux
        return File(home, "Library/Application Support/Firefox")
    }

    /** The same set [CookieImport] keeps, for the same reasons - see there. */
    private val WANTED = setOf(
        "SAPISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "APISID",
        "SID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "HSID",
        "SSID",
        "LOGIN_INFO",
        "PREF",
        "VISITOR_INFO1_LIVE",
    )
}
