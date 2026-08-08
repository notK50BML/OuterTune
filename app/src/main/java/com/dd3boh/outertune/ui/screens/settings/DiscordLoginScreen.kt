/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * Adapted from reocat/OuterTune, which is GPL-3.0. See git history for contributors.
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DiscordTokenKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Signs in to Discord in a WebView and lifts the account token back out of the page.
 *
 * There is no official Discord API for setting a rich presence from Android, so the token is used
 * to open a gateway connection as the account itself. The token never leaves the device — it is
 * written to this app's DataStore and used only to talk to Discord.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    var discordToken by rememberPreference(DiscordTokenKey, "")
    // Held in state rather than a plain local: a plain local resets to null on every
    // recomposition, which silently disabled the back handler and the poll below.
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }
    var gaveUp by remember { mutableStateOf(false) }

    // Poll rather than checking once. onPageFinished fires when the document finishes loading,
    // but Discord is a single-page app that writes its token some unpredictable moment later,
    // and no further page loads happen after that. A single check therefore races the app and,
    // when it loses, never retries - which looks exactly like the screen hanging.
    LaunchedEffect(webView, captured) {
        val view = webView ?: return@LaunchedEffect
        if (captured) return@LaunchedEffect
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            if (captured) return@LaunchedEffect
            withContext(Dispatchers.Main) {
                view.evaluateJavascript(TOKEN_EXTRACTION_JS, null)
            }
        }
        Log.w(TAG, "Gave up waiting for a Discord token after ${POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
        gaveUp = true
    }

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                // Start from a clean session so a previous login can't be silently reused.
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                WebStorage.getInstance().deleteAllData()

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onRetrieveToken(token: String) {
                        val clean = normalizeDiscordToken(token)
                        if (clean != "null" && clean != "error" && clean.isNotBlank()) {
                            captured = true
                            discordToken = clean
                            scope.launch(Dispatchers.Main) {
                                webView?.loadUrl("about:blank")
                                navController.navigateUp()
                            }
                        } else {
                            Log.w(TAG, "Token extraction returned: $token")
                        }
                    }
                }, "Android")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Only once Discord has actually landed on the app does local storage
                        // hold a token.
                        if (url.contains("/channels/@me") || url.contains("/app")) {
                            view.evaluateJavascript(TOKEN_EXTRACTION_JS, null)
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = false
                }

                // Discord's own bundle sandboxes local storage away from the top frame, so the
                // fallback path in the script above reads it from a same-origin iframe and hands
                // the value back through a JS alert.
                webChromeClient = object : WebChromeClient() {
                    override fun onJsAlert(
                        view: WebView,
                        url: String,
                        message: String,
                        result: JsResult
                    ): Boolean {
                        val clean = normalizeDiscordToken(message)
                        if (clean != "null" && clean != "error" && clean.isNotBlank()) {
                            captured = true
                            discordToken = clean
                            scope.launch(Dispatchers.Main) {
                                view.loadUrl("about:blank")
                                navController.navigateUp()
                            }
                        }
                        result.confirm()
                        return true
                    }
                }

                webView = this
                loadUrl("https://discord.com/login")
            }
        }
    )

    // Discord does not always keep its token anywhere a script in the page can read. When that
    // happens there is nothing to wait for, so say so and point at the way that always works,
    // rather than leaving a fully loaded Discord sitting there looking busy.
    if (gaveUp && !captured) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.discord_login_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.action_login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

private const val TAG = "DiscordLogin"

/** ~30 seconds of polling before giving up. */
private const val POLL_ATTEMPTS = 60
private const val POLL_INTERVAL_MS = 500L

/**
 * Strips the quotes a token can arrive wrapped in.
 *
 * localStorage stores it JSON-encoded, so `getItem` hands back `"MTIz..."` with the quote
 * characters as part of the value. Pasted tokens pick them up too, from copying a console
 * result. A Discord token never legitimately contains a quote, so removing a matched pair is
 * safe and saves a login that would otherwise fail with a baffling 4004.
 */
internal fun normalizeDiscordToken(raw: String): String {
    val t = raw.trim()
    val quoted = t.length >= 2 &&
            ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
    return (if (quoted) t.substring(1, t.length - 1) else t).trim()
}

/**
 * Three ways to get the token, tried in order of how well they hold up.
 *
 * 1. Ask Discord's own webpack registry for the module that owns the token and call its
 *    getToken(). This is the only one that does not depend on where Discord happens to keep
 *    the value, so it survives the client changing its storage - which is what broke the
 *    other two.
 * 2. localStorage, for older builds that still populate it.
 * 3. A same-origin iframe's localStorage, for builds that sandbox it away from the top frame.
 *
 * Nothing here is sent anywhere: the value goes straight into this app's DataStore.
 */
private val TOKEN_EXTRACTION_JS = """
    (function() {
        function done(t) {
            if (t) { Android.onRetrieveToken(String(t)); return true; }
            return false;
        }

        // 1. Discord's module registry.
        try {
            var chunk = window.webpackChunkdiscord_app;
            if (chunk && typeof chunk.push === 'function') {
                var found = null;
                chunk.push([[Symbol('ot')], {}, function(req) {
                    try {
                        var cache = req.c || {};
                        for (var id in cache) {
                            if (found) break;
                            try {
                                var mod = cache[id];
                                var ex = mod && mod.exports;
                                if (!ex) continue;
                                // The token store is sometimes the export itself and sometimes
                                // sitting behind a minified wrapper key.
                                var candidates = [ex, ex.default, ex.Z, ex.ZP];
                                for (var i = 0; i < candidates.length; i++) {
                                    var c = candidates[i];
                                    if (c && c.setToken && typeof c.getToken === 'function') {
                                        var t = c.getToken();
                                        if (t) { found = t; break; }
                                    }
                                }
                            } catch (e) {}
                        }
                    } catch (e) {}
                }]);
                if (done(found)) return;
            }
        } catch (e) {}

        // 2. localStorage on the top frame.
        try {
            var token = localStorage.getItem("token");
            if (done(token)) return;
        } catch (e) {}

        // 3. localStorage via a same-origin iframe.
        try {
            var i = document.createElement('iframe');
            document.body.appendChild(i);
            setTimeout(function() {
                try {
                    var alt = i.contentWindow.localStorage.token;
                    alert(alt ? String(alt) : "null");
                } catch (e) {
                    alert("error");
                }
            }, 1000);
        } catch (e) {
            alert("error");
        }
    })();
""".trimIndent()
