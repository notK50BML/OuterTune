/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * Music recognition is based on the MusicRecognizer project by Aleksey Saenko, by way of
 * Metrolist. See git history for contributors.
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.recognition.MusicRecognitionService
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.urlEncode
import com.dd3boh.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.launch

/**
 * Listens through the microphone and asks what is playing.
 *
 * A fingerprint of about twelve seconds of audio is computed on the device and only that
 * fingerprint is sent - the recording itself never leaves the phone, and is not written to disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MusicRecognitionService.recognitionStatus.collectAsState()

    var permissionDenied by remember { mutableStateOf(false) }

    val start: () -> Unit = {
        scope.launch { MusicRecognitionService.recognize(context) }
        Unit
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else permissionDenied = true
    }

    // Listen as soon as the screen opens. Anyone who navigated here wants the answer now, and
    // making them press a second button to begin is a wasted step.
    LaunchedEffect(Unit) {
        if (MusicRecognitionService.hasRecordPermission(context)) {
            start()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Leaving mid-listen must release the microphone and clear the result, or coming back shows
    // the last answer as though it were fresh.
    DisposableEffect(Unit) {
        onDispose { MusicRecognitionService.reset() }
    }

    val listening = status is RecognitionStatus.Listening || status is RecognitionStatus.Processing

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        PulsingMic(active = listening)
        Spacer(Modifier.height(32.dp))

        Text(
            text = when {
                permissionDenied -> stringResource(R.string.recognition_no_permission)
                status is RecognitionStatus.Listening -> stringResource(R.string.recognition_listening)
                status is RecognitionStatus.Processing -> stringResource(R.string.recognition_processing)
                status is RecognitionStatus.Success ->
                    (status as RecognitionStatus.Success).result.title
                status is RecognitionStatus.NoMatch -> stringResource(R.string.recognition_no_match)
                status is RecognitionStatus.Error -> (status as RecognitionStatus.Error).message
                else -> stringResource(R.string.recognition_ready)
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )

        (status as? RecognitionStatus.Success)?.let { success ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = success.result.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                // Handed to the existing search rather than played directly: Shazam answers with
                // a title and an artist, not a YouTube Music id, so there is nothing to play yet.
                val query = "${success.result.title} ${success.result.artist}"
                navController.navigate("search/${query.urlEncode()}")
            }) {
                Text(stringResource(R.string.recognition_search_for_it))
            }
        }

        if (!listening && status !is RecognitionStatus.Success) {
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                permissionDenied = false
                if (MusicRecognitionService.hasRecordPermission(context)) start()
                else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Text(stringResource(R.string.recognition_try_again))
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.recognition)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
    )
}

/**
 * A microphone with rings breathing out of it while it listens.
 *
 * Not driven by the microphone level: reading the amplitude would mean tapping the same
 * AudioRecord the fingerprint depends on, and a decorative animation is no reason to put anything
 * near that. The rings are read inside [drawBehind], so each frame redraws without recomposing.
 */
@Composable
private fun PulsingMic(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "mic")
    val phase = if (active) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        ).value
    } else {
        0f
    }

    val ringColor = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .drawBehind {
                    if (!active) return@drawBehind
                    val base = size.minDimension / 5f
                    // Three rings a third of a cycle apart, each fading as it grows, so the
                    // sequence loops without a visible restart.
                    repeat(3) { i ->
                        val t = (phase + i / 3f) % 1f
                        val radius = base * (1f + t * 1.9f)
                        drawCircle(
                            color = ringColor.copy(alpha = 0.32f * (1f - t)),
                            radius = radius,
                            center = Offset(size.width / 2f, size.height / 2f),
                        )
                    }
                }
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
    }
}
