/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.util.Collections

/**
 * Album art, fetched once per URL and then kept.
 *
 * A cache is not an optimisation here so much as a correctness matter for the UI: a lazy list
 * recycles rows constantly, so without one every scroll would re-request the same handful of images
 * and the list would flicker as they reloaded. Held in a plain synchronized map because the whole
 * point is that it is small and shared - there are only ever as many entries as there are distinct
 * covers on screen and recently played.
 *
  * Bounded, and the bound matters more than it looks. A cover decodes to roughly 1.2MB of RGBA, so
 * the first version of this - capped at 300 - was quietly authorising 350MB of bitmaps, which is a
 * large part of why the app measured heavier than a music player should. Sixty is comfortably more
 * than can be on screen at once plus a full recently-played shelf, and about 70MB at worst.
 *
 * Cleared wholesale at the cap rather than evicting cleverly: the cost of being wrong is one reload
 * of a visible image, which is not worth an LRU.
 */
internal object ArtworkCache {
    private const val MAX_ENTRIES = 60
    private val images = Collections.synchronizedMap(HashMap<String, ImageBitmap>())
    private val client by lazy { HttpClient(OkHttp) }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        images[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes: ByteArray = client.get(url).body()
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()?.also {
                if (images.size >= MAX_ENTRIES) images.clear()
                images[url] = it
            }
        }
    }
}

/**
 * Asks the CDN for a cover at the size it will be drawn.
 *
 * YouTube hands back whatever thumbnail suited the response it came from - browse and search
 * payloads routinely carry 60-226px art - and using those verbatim means everything larger than a
 * list row is an upscale of a postage stamp. The size is part of the URL, so asking for a bigger one
 * costs nothing but writing a different number.
 *
 * The same reasoning as the Android app's own artwork sizing; the pattern is duplicated rather than
 * shared because that helper lives in the Android module.
 */
private fun String.atSize(pixels: Int): String = when {
    isBlank() -> this
    contains("=w") && contains("-h") -> substringBefore("=w") + "=w$pixels-h$pixels-p-l90-rj"
    contains("=s") -> substringBefore("=s") + "=s$pixels"
    else -> this
}

/**
 * Two colours pulled out of a cover, for tinting the player behind it.
 *
 * Averaging every pixel gives mud - album art is mostly midtones, and the mean of a colourful image
 * is reliably grey. Instead the pixels are bucketed coarsely by hue and lightness, near-greys are
 * dropped, and the two fullest buckets win. That keeps the colour a person would name if asked what
 * colour the cover is, which is the point.
 *
 * Only a grid of samples is read rather than every pixel: a cover is a few hundred pixels square and
 * the answer does not change for reading all of them.
 */
fun ImageBitmap.dominantColours(): Pair<Color, Color> {
    val pixels = runCatching { toPixelMap() }.getOrNull() ?: return DEFAULT_COLOURS
    val buckets = HashMap<Int, MutableList<Color>>()
    val step = maxOf(1, minOf(width, height) / 32)

    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val colour = runCatching { pixels[x, y] }.getOrNull()
            if (colour != null && colour.alpha > 0.5f) {
                val max = maxOf(colour.red, colour.green, colour.blue)
                val min = minOf(colour.red, colour.green, colour.blue)
                // Saturation and lightness both matter: a near-grey has nothing to contribute, and
                // near-black or near-white would swamp everything else since covers have a lot of both.
                if (max - min > 0.12f && max > 0.15f && min < 0.95f) {
                    val key = (colour.red * 4).toInt() * 100 + (colour.green * 4).toInt() * 10 +
                        (colour.blue * 4).toInt()
                    buckets.getOrPut(key) { mutableListOf() }.add(colour)
                }
            }
            y += step
        }
        x += step
    }

    val ranked = buckets.values.sortedByDescending { it.size }
    if (ranked.isEmpty()) return DEFAULT_COLOURS
    val first = ranked[0].average()
    val second = ranked.getOrNull(1)?.average() ?: first
    return first to second
}

private fun List<Color>.average(): Color = Color(
    red = sumOf { it.red.toDouble() }.toFloat() / size,
    green = sumOf { it.green.toDouble() }.toFloat() / size,
    blue = sumOf { it.blue.toDouble() }.toFloat() / size,
)

/** Used when a cover has no usable colour, or none has loaded yet. */
private val DEFAULT_COLOURS = Color(0xFF2A2A32) to Color(0xFF14141A)

/** The cover's colours, recomputed only when the URL changes. */
@Composable
fun rememberArtworkColours(url: String?): Pair<Color, Color> {
    var colours by remember(url) { mutableStateOf(DEFAULT_COLOURS) }
    LaunchedEffect(url) {
        // A small copy is plenty for sampling colour, and it is usually already cached from a row.
        colours = url?.takeIf { it.isNotBlank() }
            ?.let { ArtworkCache.load(it.atSize(128))?.dominantColours() }
            ?: DEFAULT_COLOURS
    }
    return colours
}

/**
 * A square cover, or a blank tile of the right size while it loads or if it never arrives.
 *
 * Always occupying its space matters more than it sounds: a cover that appears only once loaded
 * would reflow every row it lands in, so the list would jitter as it scrolled.
 */
@Composable
fun Artwork(url: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    // Requested at twice the drawn size, so it stays sharp on a high-DPI display and when the
    // window is scaled. Rounded up to a sane step rather than the exact dp, so a handful of sizes
    // are requested across the app instead of one cached image per pixel dimension.
    val pixels = remember(size) { ((size.value.toInt() * 2 + 63) / 64 * 64).coerceIn(64, 720) }
    var image by remember(url, pixels) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, pixels) {
        image = url?.takeIf { it.isNotBlank() }?.let { ArtworkCache.load(it.atSize(pixels)) }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}
