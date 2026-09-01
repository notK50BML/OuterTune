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
import androidx.compose.ui.graphics.ImageBitmap
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
 * Bounded, because a long session searching would otherwise accumulate every cover it ever saw.
 * When the cap is reached the cache is cleared rather than evicting cleverly: the cost of being
 * wrong is one reload of a visible image, which is not worth an LRU for.
 */
private object ArtworkCache {
    private const val MAX_ENTRIES = 300
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
 * A square cover, or a blank tile of the right size while it loads or if it never arrives.
 *
 * Always occupying its space matters more than it sounds: a cover that appears only once loaded
 * would reflow every row it lands in, so the list would jitter as it scrolled.
 */
@Composable
fun Artwork(url: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = url?.let { ArtworkCache.load(it) }
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
