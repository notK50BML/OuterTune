/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File

/**
 * Reads a Netscape `cookies.txt` file and pulls out a YouTube session.
 *
 * The format every cookie-export extension writes and the one `yt-dlp --cookies` takes, which is the
 * point: the user installs an extension they can find, clicks it, and saves a file. That is
 * teachable in a sentence, unlike "open developer tools, find the network tab, filter the requests,
 * copy the Cookie header".
 *
 * See SIGN-IN.md for why this is the approach rather than an OAuth flow or an embedded browser. The
 * short version: authentication here is a logged-in Google web session, not a token, and no OAuth
 * grant produces one.
 *
 * The format is one cookie per line, tab-separated:
 *
 *     domain  includeSubdomains  path  secure  expiry  name  value
 *
 * A leading `#` is a comment, except for `#HttpOnly_`, which some exporters prefix to a real line -
 * and those are exactly the lines that matter here, since Google's session cookies are HttpOnly.
 * Treating them as comments discards the whole credential and leaves a file that looks fine.
 */
object CookieImport {

    /**
     * The cookies that make up a Google session.
     *
     * `SAPISID` is the only one that is cryptographically required - it alone feeds the SAPISIDHASH
     * signature - but the server expects a coherent session, so the companions are carried too.
     */
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

    /** What a file yielded, or why it did not. */
    sealed interface Result {
        /** A cookie string ready for `YouTube.cookie`. */
        data class Session(val cookie: String, val names: List<String>) : Result

        /** The file parsed but held no Google session - usually the wrong site, or signed out. */
        data object NoSession : Result

        /** The file could not be read or is not in this format. */
        data class Unreadable(val reason: String) : Result
    }

    fun fromFile(file: File): Result {
        val text = runCatching { file.readText() }
            .getOrElse { return Result.Unreadable("Could not read the file: ${it.message}") }
        return fromText(text)
    }

    fun fromText(text: String): Result {
        val found = LinkedHashMap<String, String>()
        var sawAnyCookieLine = false

        text.lineSequence().forEach { rawLine ->
            // #HttpOnly_ marks a real cookie, not a comment. Google's session cookies are all
            // HttpOnly, so skipping these lines throws away precisely the ones needed and leaves a
            // file that appears to have parsed.
            val line = rawLine.removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith("#")) return@forEach

            val parts = line.split('\t')
            if (parts.size < 7) return@forEach
            sawAnyCookieLine = true

            val domain = parts[0].removePrefix(".")
            if (!domain.endsWith("youtube.com") && !domain.endsWith("google.com")) return@forEach

            val name = parts[5]
            val value = parts[6]
            if (name !in WANTED || value.isBlank()) return@forEach
            // First occurrence wins. An export can carry the same name for several domains, and the
            // youtube.com one is listed first in every exporter seen; overwriting would swap in
            // whichever happened to come last.
            found.putIfAbsent(name, value)
        }

        if (!sawAnyCookieLine) {
            return Result.Unreadable("That does not look like a cookies.txt file.")
        }
        // The one value without which nothing can be signed - see SIGN-IN.md.
        if ("SAPISID" !in found) return Result.NoSession

        val cookie = found.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return Result.Session(cookie, found.keys.toList())
    }
}
