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
     * Session-bound PoTokens - the kind the *player request* carries - keyed by the identity each
     * was minted against. There is deliberately more than one: a single resolve attempts several
     * clients, and keying instead of overwriting lets their identities coexist without tearing down
     * and rebuilding the WebView on every client switch.
     */
    private val webPoTokenSessionPots = mutableMapOf<String, String>()
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

        val (poTokenGenerator, sessionPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate = forceRecreate || webPoTokenGenerator == null ||
                        webPoTokenGenerator!!.isExpired || webPoTokenInvalidated

                if (shouldRecreate) {
                    webPoTokenInvalidated = false
                    // Tokens minted by the outgoing generator do not outlive it.
                    webPoTokenSessionPots.clear()

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    // create a new webPoTokenGenerator
                    webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(App.instance)
                }

                // The session-bound token has to be minted exactly once per identity, and before
                // any video-bound token is minted from this generator.
                val sessionPot = webPoTokenSessionPots.getOrPut(sessionId) {
                    webPoTokenGenerator!!.generatePoToken(sessionId)
                }

                Triple(webPoTokenGenerator!!, sessionPot, shouldRecreate)
            }

        val videoBoundPot = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // session poToken (based on [sessionId]) to be generated before anything else.
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

        if (POTOKEN_DEBUG) {
            Log.d(TAG, "[$videoId] sessionPot=$sessionPot, videoBoundPot=$videoBoundPot")
        }

        // Which binding goes where is not interchangeable, and getting it backwards fails in a way
        // that looks like success. YouTube's current web enforcement is GVS-only: the token on the
        // stream url must be bound to the *video*, and the player request's token to the *session*.
        // A session-bound token on the stream url is, as far as googlevideo is concerned, no token
        // at all - it serves the first buffer and then refuses with a 403 about a minute in, which
        // is indistinguishable from an expired url right up to the point it isn't. Metrolist's
        // PlaybackClientCatalog states the rule outright, including that a video-bound GVS token
        // must not be reused for the player request.
        return PoTokenResult(
            playerRequestPoToken = sessionPot,
            streamingDataPoToken = videoBoundPot,
        )
    }
}