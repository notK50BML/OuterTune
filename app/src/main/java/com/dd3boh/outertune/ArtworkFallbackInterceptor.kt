/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.artworkFallbackToLowRes

/**
 * A high-res artwork request (see [com.dd3boh.outertune.utils.remoteArtwork]) occasionally gets
 * rejected or times out on a size the CDN would have served fine at something smaller - retries
 * once at a conservative fallback size rather than showing nothing. Only retries String-URL
 * requests recognized by [resize] as already being a resized artwork URL, so a genuinely broken
 * URL or an unrelated network image fails normally instead of silently retrying forever.
 */
private const val FALLBACK_SIZE = 320

class ArtworkFallbackInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is ErrorResult || !artworkFallbackToLowRes) return result

        val data = chain.request.data as? String ?: return result
        val fallbackUrl = data.resize(FALLBACK_SIZE, FALLBACK_SIZE)
        if (fallbackUrl == data) return result // not a recognized resizable artwork URL

        val fallbackRequest = chain.request.newBuilder()
            .data(fallbackUrl)
            .build()
        return chain.withRequest(fallbackRequest).proceed()
    }
}
