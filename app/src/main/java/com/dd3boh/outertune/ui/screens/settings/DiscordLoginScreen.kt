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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * Signs in to Discord in a WebView and lifts the account token out of local storage.
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
                        if (token != "null" && token != "error" && token.isNotBlank()) {
                            captured = true
                            discordToken = token
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
                        if (message != "null" && message != "error" && message.isNotBlank()) {
                            captured = true
                            discordToken = message
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

private val TOKEN_EXTRACTION_JS = """
    (function() {
        try {
            var token = localStorage.getItem("token");
            if (token) {
                Android.onRetrieveToken(token.slice(1, -1));
            } else {
                var i = document.createElement('iframe');
                document.body.appendChild(i);
                setTimeout(function() {
                    try {
                        var alt = i.contentWindow.localStorage.token;
                        if (alt) {
                            alert(alt.slice(1, -1));
                        } else {
                            alert("null");
                        }
                    } catch (e) {
                        alert("error");
                    }
                }, 1000);
            }
        } catch (e) {
            alert("error");
        }
    })();
""".trimIndent()
