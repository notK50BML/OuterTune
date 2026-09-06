/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Who is signed in, if anyone, and what the last attempt to find out concluded. */
sealed interface AccountState {
    data object SignedOut : AccountState

    data class SignedIn(
        val name: String,
        val email: String?,
        val thumbnailUrl: String?,
    ) : AccountState

    /**
     * A stored session that no longer works.
     *
     * Distinct from [SignedOut] on purpose: the two need different words. Somebody who has never
     * signed in needs an invitation; somebody whose cookie expired needs to know that is what
     * happened, because otherwise the app has silently stopped showing their library and the first
     * question is always "am I still signed in".
     */
    data class Expired(val reason: String) : AccountState
}

/**
 * Owns the signed-in session.
 *
 * The credential is a Google web session cookie, not a token - see SIGN-IN.md for why, and why no
 * OAuth flow produces one. Four ways in, all landing on the same string:
 *
 * - sign in inside the user's own browser, read back over the DevTools Protocol ([ChromeSignIn]),
 * - read straight out of Firefox ([FirefoxCookies]),
 * - a `cookies.txt` export from any browser ([CookieImport]),
 * - pasted by hand, for anyone who already has it.
 *
 * Every route is verified against the account endpoint before being stored, so a bad credential
 * fails where the user is looking rather than silently three screens later. That is the difference
 * between a sign-in that works and one that appears to.
 */
class Account(private val db: Database) {

    val state = MutableStateFlow<AccountState>(AccountState.SignedOut)

    /**
     * Applies a stored session, if there is one.
     *
     * Called at startup before anything requests. Sets [YouTube.cookie] immediately and verifies
     * afterwards, so the session is in force for the first request rather than a round trip later -
     * but a session that has expired since last time is reported rather than left to fail every
     * request with no explanation.
     */
    suspend fun restore() {
        val stored = db.credential(COOKIE_KEY) ?: return
        YouTube.cookie = stored
        when (val result = verify(stored)) {
            is AccountState.SignedIn -> state.value = result
            else -> state.value = result
        }
    }

    /** Verifies [cookie] and keeps it if it works. Returns what happened, for the UI to show. */
    suspend fun signIn(cookie: String): AccountState {
        val result = verify(cookie)
        if (result is AccountState.SignedIn) {
            db.putCredential(COOKIE_KEY, cookie)
            YouTube.cookie = cookie
        }
        state.value = result
        return result
    }

    fun signOut() {
        db.putCredential(COOKIE_KEY, null)
        YouTube.cookie = null
        state.value = AccountState.SignedOut
    }

    /**
     * Asks YouTube who this cookie belongs to.
     *
     * The cheapest authenticated call there is, and the only honest test: a cookie can be
     * well-formed, contain a `SAPISID`, and still be signed out, expired or for a different
     * property. Parsing it proves nothing.
     */
    private suspend fun verify(cookie: String): AccountState = withContext(Dispatchers.IO) {
        YouTube.accountInfo(
            cookie = cookie,
            visitorData = YouTube.visitorData,
            // Only used to pick between channels on a multi-channel account. The desktop has no way
            // to read it - it comes from JavaScript on the page - so the default channel is used.
            dataSyncId = null,
        ).fold(
            onSuccess = { AccountState.SignedIn(it.name, it.email, it.thumbnailUrl) },
            onFailure = {
                AccountState.Expired(
                    it.message?.takeIf { m -> m.isNotBlank() }
                        ?: "That session did not work. It may have expired."
                )
            },
        )
    }

    /** The four ways a credential can arrive, in one place so the screen has no logic in it. */
    suspend fun signInFromFirefox(profile: FirefoxCookies.Profile): AccountState =
        applyImport(withContext(Dispatchers.IO) { FirefoxCookies.read(profile) })

    /**
     * Opens the user's own browser and waits for them to sign in there.
     *
     * The best of the routes when a Chromium is installed: nothing to export, nothing to find, and
     * the sign-in happens in a real browser so everything Google puts in the way of it works. See
     * [ChromeSignIn] for why that is possible without bundling one.
     */
    suspend fun signInWithBrowser(onProgress: (ChromeSignIn.Progress) -> Unit = {}): AccountState {
        val cookie = ChromeSignIn.signIn(onProgress = onProgress)
            ?: return AccountState.Expired("Sign-in was not completed.").also { state.value = it }
        return signIn(cookie)
    }

    suspend fun signInFromFile(file: File): AccountState =
        applyImport(withContext(Dispatchers.IO) { CookieImport.fromFile(file) })

    suspend fun signInFromPastedText(text: String): AccountState {
        // Accepts either a cookies.txt file pasted whole or a raw Cookie header. Telling them apart
        // by content rather than asking: a tab-separated line is the file format, anything with
        // name=value pairs is a header, and making the user classify their own clipboard is a step
        // that exists only for the program's benefit.
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AccountState.Expired("Nothing to sign in with.")
        return if (trimmed.contains('\t')) {
            applyImport(CookieImport.fromText(trimmed))
        } else {
            signIn(trimmed)
        }
    }

    private suspend fun applyImport(result: CookieImport.Result): AccountState = when (result) {
        is CookieImport.Result.Session -> signIn(result.cookie)
        CookieImport.Result.NoSession -> AccountState.Expired(
            "That browser is not signed in to YouTube Music."
        ).also { state.value = it }
        is CookieImport.Result.Unreadable -> AccountState.Expired(result.reason).also { state.value = it }
    }

    private companion object {
        const val COOKIE_KEY = "youtube_cookie"
    }
}
