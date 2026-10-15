/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KizzyRepository.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.repository

import com.my.kizzy.remote.ApiService
import java.util.concurrent.ConcurrentHashMap

/**
 * Modified by Zion Huang
 */
class KizzyRepository(token: String) {
    private val api = ApiService(token)

    /**
     * Resolved assets, by image URL.
     *
     * Every track asks for two - the cover and the artist's picture - and on an album being played
     * through, the cover is the same URL every time. Without this, that is one network round trip
     * per track for an answer that cannot have changed, sitting between the track starting and its
     * presence card appearing.
     *
     * Bounded, and cleared wholesale rather than evicted one at a time. This is a small map of
     * short strings; the cap exists so a long shuffle cannot grow it without limit, and at that
     * size the difference between dropping the oldest and dropping all of them is one extra round
     * trip for whatever is playing next.
     */
    private val resolved = ConcurrentHashMap<String, String>()

    /**
     * Why the last resolution failed, or null if the last one worked.
     *
     * Kept because the failure mode here is silence: an unresolved image becomes a null asset and
     * the card simply renders without art, which looks like Discord being slow rather than like
     * anything being wrong. That is how a dead image service went unnoticed - see [ApiService].
     */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun getImage(applicationId: String?, url: String): String? {
        if (applicationId.isNullOrBlank()) {
            lastError = "No application id, so Discord has nothing to register the image against"
            return null
        }
        resolved[url]?.let { return it }

        return api.externalAsset(applicationId, url)
            .onSuccess { asset ->
                if (resolved.size >= MAX_CACHED) resolved.clear()
                resolved[url] = asset
                lastError = null
            }
            .onFailure { lastError = it.message ?: it::class.simpleName }
            .getOrNull()
    }

    private companion object {
        const val MAX_CACHED = 64
    }
}
