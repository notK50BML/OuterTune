package com.dd3boh.outertune.utils.potoken

import android.util.Log
import android.webkit.CookieManager
import com.dd3boh.outertune.App
import com.dd3boh.outertune.constants.POTOKEN_DEBUG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()

    /**
     * Streaming (session-bound) PoTokens, keyed by the identity each was minted against. There is
     * deliberately more than one: a request that carries the account cookie has to present a token
     * bound to dataSyncId, while an anonymous one needs visitorData, and a single resolve attempts
     * both kinds of client. Keying instead of overwriting is what lets those coexist without
     * tearing down and rebuilding the WebView on every client switch.
     */
    private val webPoTokenStreamingPots = mutableMapOf<String, String>()
    private var webPoTokenGenerator: PoTokenWebView? = null
    private var webPoTokenInvalidated = false

    /**
     * Forces the next [getWebClientPoToken] call to mint a fresh streaming token and, if needed,
     * recreate the WebView generator from scratch - instead of reusing state that may be exactly
     * why the last request was rejected. [webPoTokenSessionId] is what [getWebClientPoToken]
     * already checks to decide whether to recreate, so setting this is enough.
     */
    fun invalidate() {
        webPoTokenInvalidated = true
    }

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) {
            return null
        }

        return try {
            runBlocking { getWebClientPoToken(videoId, sessionId, forceRecreate = false) }
        } catch (e: Exception) {
            when (e) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
                    webViewBadImpl = true
                    null
                }
                else -> throw e // includes PoTokenException
            }
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        if (POTOKEN_DEBUG) Log.d(TAG, "Web poToken requested: $videoId, $sessionId")

        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate = forceRecreate || webPoTokenGenerator == null ||
                        webPoTokenGenerator!!.isExpired || webPoTokenInvalidated

                if (shouldRecreate) {
                    webPoTokenInvalidated = false
                    // Tokens minted by the outgoing generator do not outlive it.
                    webPoTokenStreamingPots.clear()

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    // create a new webPoTokenGenerator
                    webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(App.instance)
                }

                // The streaming poToken for an identity needs to be generated exactly once, and
                // before any other (player) token is minted from this generator for it.
                val streamingPotForSession = webPoTokenStreamingPots.getOrPut(sessionId) {
                    webPoTokenGenerator!!.generatePoToken(sessionId)
                }

                Triple(webPoTokenGenerator!!, streamingPotForSession, shouldRecreate)
            }

        val playerPot = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // streaming poToken (based on [sessionId]) to be generated before anything else.
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if the app goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        if (POTOKEN_DEBUG) Log.d(TAG, "[$videoId] playerPot=$playerPot, streamingPot=$streamingPot")

        return PoTokenResult(playerPot, streamingPot)
    }
}