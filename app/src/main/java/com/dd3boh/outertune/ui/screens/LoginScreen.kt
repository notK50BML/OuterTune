package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalAccountImageFetcher
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AccountImageFetchedKey
import com.dd3boh.outertune.constants.AccountImageUrlKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val accountImageFetcher = LocalAccountImageFetcher.current

    // Once the cookie shows an established session, leave the login screen and return to the
    // start destination (the main screen) automatically.
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            navController.popBackStack(navController.graph.startDestinationId, false)
        }
    }

    var webView: WebView? = null

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    private var loginHandled = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        // window.yt only exists on music.youtube.com, not on the sign-in pages.
                        if (url?.startsWith("https://music.youtube.com") != true) return
                        val cookie = CookieManager.getInstance().getCookie(url) ?: return

                        // Act only once the cookie shows an established session, so the fetch is not
                        // started during intermediate, not-yet-signed-in page loads.
                        if (loginHandled || "SAPISID" !in parseCookieString(cookie)) return
                        loginHandled = true

                        // Publish the cookie to the shared YouTube state immediately so that other
                        // requests use the new session while the DataStore collector catches up.
                        YouTube.cookie = cookie

                        val visitorData = CompletableDeferred<String?>()
                        val dataSyncId = CompletableDeferred<String?>()
                        view.evaluateJavascript("window.yt.config_.VISITOR_DATA") {
                            visitorData.complete(decodeEvaluatedString(it))
                        }
                        view.evaluateJavascript("window.yt.config_.DATASYNC_ID") {
                            dataSyncId.complete(decodeEvaluatedString(it))
                        }

                        // Not tied to this screen: it closes as soon as the cookie is stored, which
                        // would cancel a write and a fetch started from a screen-scoped coroutine.
                        GlobalScope.launch {
                            val newVisitorData = visitorData.await()
                            val newDataSyncId = dataSyncId.await()?.substringBefore("||")
                            context.dataStore.edit { settings ->
                                settings[InnerTubeCookieKey] = cookie
                                newVisitorData?.let { settings[VisitorDataKey] = it }
                                newDataSyncId?.let { settings[DataSyncIdKey] = it }
                                // The stored image belongs to the account being replaced.
                                settings.remove(AccountImageUrlKey)
                                settings.remove(AccountImageFetchedKey)
                            }
                            accountImageFetcher.fetch()
                        }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                }
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        }
    )

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

/**
 * Reads a value out of an [WebView.evaluateJavascript] result, which is JSON encoded: strings come
 * back quoted and escaped, and an undefined value comes back as `null`.
 */
private fun decodeEvaluatedString(result: String?): String? =
    result?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
