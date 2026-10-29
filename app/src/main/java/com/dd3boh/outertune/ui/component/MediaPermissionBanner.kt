/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL

/**
 * The red strip at the top of the library screens saying the app cannot read local music, which
 * asks for the permission when tapped.
 *
 * One composable for every screen that shows it, so they cannot drift apart again: each kept its
 * own copy, and all of them drew white text on the theme's error colour, which is a pale red in the
 * dark theme and left the warning unreadable. onError is the colour made to sit on it.
 *
 * Shown only while [enabled] (the local library is on) and the permission is missing. A tap hides
 * it straight away whatever the answer, so someone who declines is not nagged by it for the rest
 * of the visit.
 */
@Composable
fun MediaPermissionBanner(enabled: Boolean) {
    val context = LocalContext.current
    var showStoragePerm by remember {
        mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
    }
    if (!enabled || !showStoragePerm) return

    TextButton(
        onClick = {
            showStoragePerm = false
            (context as? MainActivity)?.permissionLauncher?.launch(MEDIA_PERMISSION_LEVEL)
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
    ) {
        Text(
            text = stringResource(R.string.missing_media_permission_warning),
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
