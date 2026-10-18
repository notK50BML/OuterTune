/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayerLayoutKey
import com.dd3boh.outertune.models.PlayerLayout
import com.dd3boh.outertune.utils.rememberPreference

/**
 * The player layout editor, inside the app - the same tools/layout-editor.html the Windows app
 * ships, bundled as an asset.
 *
 * On a desktop browser the editor downloads a file for the app to import. Here that round trip is
 * pointless, so the page is handed a small bridge ("OuterTune"): it reads the layout in use to start
 * from, and its "Apply to player" button hands a new one straight back - validated exactly as an
 * imported file is, so the editor cannot leave the player in a state that needs a reinstall.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerLayoutEditorScreen(navController: NavController) {
    val context = LocalContext.current
    var layoutJson by rememberPreference(PlayerLayoutKey, "")
    val current by rememberUpdatedState(layoutJson)
    val main = Handler(Looper.getMainLooper())

    val bridge = object {
        @JavascriptInterface
        fun current(): String = current

        @JavascriptInterface
        fun apply(json: String) {
            main.post {
                PlayerLayout.parse(json)
                    .onSuccess {
                        layoutJson = json
                        Toast.makeText(context, R.string.player_layout_applied, Toast.LENGTH_SHORT).show()
                        navController.navigateUp()
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: context.getString(R.string.player_layout_unreadable), Toast.LENGTH_LONG).show()
                    }
            }
        }

        @JavascriptInterface
        fun revert() {
            main.post {
                layoutJson = ""
                Toast.makeText(context, R.string.player_layout_reset_done, Toast.LENGTH_SHORT).show()
                navController.navigateUp()
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The page only ever comes from the app's own assets; nothing else is loaded.
                settings.allowFileAccess = false
                addJavascriptInterface(bridge, "OuterTune")
                loadUrl("file:///android_asset/editor/layout-editor.html")
            }
        },
    )
}
