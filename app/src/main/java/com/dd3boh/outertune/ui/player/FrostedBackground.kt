/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.coilCoroutine
import kotlinx.coroutines.withContext

/**
 * How small the artwork is fetched before being stretched over the screen.
 *
 * The blur is mostly this: an 18px image scaled to fill a phone display is already a smooth wash
 * of colour, because the upscale interpolates between a handful of pixels. Doing it this way
 * rather than through a large blur radius means it costs almost nothing, looks identical, and -
 * unlike [Modifier.blur], which is a no-op before Android 12 - works on every version this app
 * supports.
 */
private const val FROST_SOURCE_PX = 18

/** A small extra blur on top, where the platform can do it, to take the last edges off. */
private val FROST_EXTRA_BLUR = 24.dp

/**
 * Above this relative luminance the background counts as "light", and dark text belongs on it.
 *
 * Slightly above the midpoint on purpose: white text on a mid-grey stays legible further down
 * than black text does, so the tie is broken towards keeping light text.
 */
private const val LIGHT_BACKGROUND_THRESHOLD = 0.58f

/**
 * The album art, blurred past recognition, as a background for the player.
 *
 * A scrim is drawn on top with a colour chosen from the artwork's own brightness. Without it a
 * pale cover leaves white controls on near-white, and a very dark one swallows them; with it the
 * background keeps the cover's character while the foreground stays readable.
 */
@Composable
fun FrostedBackground(
    mediaMetadata: MediaMetadata?,
    isLight: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = mediaMetadata?.getThumbnailModel(FROST_SOURCE_PX, FROST_SOURCE_PX),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .blur(FROST_EXTRA_BLUR),
        )

        // Frosted glass is a light scattering layer over the image, not a flat wash - hence the
        // gradient rather than a single alpha. Denser at the bottom, where the controls sit.
        val scrim = if (isLight) Color.White else Color.Black
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scrim.copy(alpha = if (isLight) 0.35f else 0.30f),
                            scrim.copy(alpha = if (isLight) 0.55f else 0.50f),
                        )
                    )
                )
        )
    }
}

/**
 * Whether the frosted background for [mediaMetadata] is light enough to need dark text.
 *
 * Returns null while unknown, so callers can keep whatever they were using rather than flashing
 * through a wrong colour on every track change.
 *
 * The measurement is taken from the same tiny bitmap the background is drawn from, which is both
 * cheap and the right thing to measure: it is the blurred average that sits behind the text, not
 * any particular pixel of the full-size cover.
 */
@Composable
fun rememberCoverIsLight(mediaMetadata: MediaMetadata?, enabled: Boolean): Boolean? {
    val context = LocalContext.current
    var isLight by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(mediaMetadata, enabled) {
        if (!enabled || mediaMetadata == null) {
            isLight = null
            return@LaunchedEffect
        }
        withContext(coilCoroutine) {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(mediaMetadata.getThumbnailModel(FROST_SOURCE_PX, FROST_SOURCE_PX))
                    // Hardware bitmaps live in graphics memory and cannot be read back pixel by
                    // pixel, which is exactly what this needs to do.
                    .allowHardware(false)
                    .build()
            )
            val luminance = result.image?.toBitmap()?.averageLuminance()
            isLight = luminance?.let { it > LIGHT_BACKGROUND_THRESHOLD }
        }
    }

    return isLight
}

/**
 * Mean relative luminance of a bitmap, 0f (black) to 1f (white).
 *
 * Uses the Rec. 709 weights rather than a plain RGB mean, because the eye is far more sensitive
 * to green than to blue: a saturated blue cover reads as dark to a viewer while a naive average
 * calls it mid-grey, and the text colour would be chosen wrongly.
 */
private fun Bitmap.averageLuminance(): Float? {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null

    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    var total = 0.0
    var counted = 0
    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        // Fully transparent pixels show the surface behind them, not the artwork, so letting
        // them count would drag every measurement towards black.
        if (alpha < 16) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        total += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        counted++
    }
    return if (counted == 0) null else (total / counted).toFloat()
}
