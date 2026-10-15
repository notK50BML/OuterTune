/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.outertune.models.EqualizerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Searches and fetches headphone correction curves from AutoEQ's public results database
 * (https://github.com/jaakkopasanen/AutoEq, MIT licensed), scoped to oratory1990's measurements -
 * the single most consistently referenced source in that database, and one whose directory layout
 * (one folder per headphone, under over-ear/in-ear/earbud) is simple enough to query directly
 * against GitHub's own API rather than needing a bundled or self-hosted index.
 *
 * AutoEQ's full database spans ~20 other measurement sources and 8000+ entries total; mirroring
 * all of it would mean either bundling a multi-hundred-megabyte dataset in the APK or standing up
 * infrastructure to serve it. Querying GitHub's directory listing live instead means no bundled
 * data to go stale, no APK size cost, and results that are always exactly what's in the repo right
 * now - at the cost of only covering one (well-regarded) measurement source rather than all of them,
 * and being unusable offline.
 *
 * Two things about doing it over GitHub's API that the first version did not account for.
 *
 * **It is rate limited, hard.** Unauthenticated callers get 60 requests an hour per address, and
 * every search spent three of them - one per category - so about twenty searches exhausted the
 * budget for the hour. The listings are then cached for [LISTING_TTL_MS]: the folder names change
 * when somebody adds a headphone to the database, which is not something that needs checking every
 * time a letter is typed. A session now costs three requests rather than three per search.
 *
 * **And being rate limited looked exactly like finding nothing.** `if (!response.isSuccessful)
 * return emptyList()` turned a 403 into an empty result, so the screen said no headphones matched
 * when the truth was that it had not been allowed to look. Failures are distinguished from
 * absences now - see [SearchOutcome] - because "nothing found" sends someone off to check their
 * spelling and "try again in a while" does not.
 */
object AutoEqRepository {
    private const val TAG = "AutoEqRepository"
    private const val OWNER = "jaakkopasanen"
    private const val REPO = "AutoEq"
    private val CATEGORIES = listOf("over-ear", "in-ear", "earbud")

    /**
     * How long a category listing is trusted.
     *
     * An hour. These are folder names in a public repository - they change when somebody adds a
     * headphone to it - and the budget being spent is sixty requests an hour, so the cache window
     * and the limit window may as well be the same size.
     */
    private const val LISTING_TTL_MS = 60L * 60 * 1000

    data class Result(val name: String, val category: String)

    /**
     * What a search found, or why it could not look.
     *
     * The distinction is the point: an empty [Found] means the database has no headphone of that
     * name, while [RateLimited] and [Unreachable] mean the question was never asked. Collapsing
     * the three into an empty list - which is what this did - tells someone to check their
     * spelling when the real answer is to wait an hour or turn their connection on.
     */
    sealed interface SearchOutcome {
        data class Found(val results: List<Result>) : SearchOutcome
        data object RateLimited : SearchOutcome
        data object Unreachable : SearchOutcome
    }

    private val httpClient = OkHttpClient()

    /**
     * Category listings, by category.
     *
     * Held for [LISTING_TTL_MS] because they are a list of folder names in a public repository:
     * they change when a headphone is added to it, and nothing is lost by being an hour behind.
     * Without this every keystroke-debounced search spent three of the sixty requests an hour an
     * unauthenticated caller gets.
     */
    private val listings = HashMap<String, CachedListing>()
    private val listingsLock = Any()

    private data class CachedListing(val names: List<String>, val fetchedAt: Long)

    /** Case-insensitive substring match against oratory1990's headphone folder names. */
    suspend fun search(query: String): SearchOutcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchOutcome.Found(emptyList())

        val matches = mutableListOf<Result>()
        var rateLimited = false
        var failed = 0
        for (category in CATEGORIES) {
            when (val listing = namesFor(category)) {
                is ListingOutcome.Names ->
                    listing.names
                        .filter { it.contains(query, ignoreCase = true) }
                        .forEach { matches += Result(it, category) }

                ListingOutcome.RateLimited -> rateLimited = true
                ListingOutcome.Failed -> failed++
            }
        }

        // A partial answer is still an answer: if two categories listed and one did not, the
        // headphone the user is looking for may well be in the two that did. Only when nothing at
        // all came back is the failure worth reporting instead of the results.
        when {
            matches.isNotEmpty() -> SearchOutcome.Found(matches.sortedBy { it.name })
            rateLimited -> SearchOutcome.RateLimited
            failed == CATEGORIES.size -> SearchOutcome.Unreachable
            else -> SearchOutcome.Found(emptyList())
        }
    }

    private sealed interface ListingOutcome {
        data class Names(val names: List<String>) : ListingOutcome
        data object RateLimited : ListingOutcome
        data object Failed : ListingOutcome
    }

    private fun namesFor(category: String): ListingOutcome {
        val now = System.currentTimeMillis()
        synchronized(listingsLock) {
            listings[category]?.let { cached ->
                if (now - cached.fetchedAt < LISTING_TTL_MS) return ListingOutcome.Names(cached.names)
            }
        }
        val fetched = listNames(category)
        if (fetched is ListingOutcome.Names) {
            synchronized(listingsLock) { listings[category] = CachedListing(fetched.names, now) }
        }
        return fetched
    }

    private fun listNames(category: String): ListingOutcome {
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/results/oratory1990/$category"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 403 with the budget exhausted is the rate limit; GitHub also uses 429. The
                    // remaining-count header is what separates a refusal to serve from a genuine
                    // forbidden, since both arrive as 403.
                    val exhausted = response.header("X-RateLimit-Remaining")?.toIntOrNull() == 0
                    return@use if (response.code == 429 || (response.code == 403 && exhausted)) {
                        Log.w(TAG, "AutoEQ listing rate limited for $category")
                        ListingOutcome.RateLimited
                    } else {
                        Log.w(TAG, "AutoEQ listing failed for $category: HTTP ${response.code}")
                        ListingOutcome.Failed
                    }
                }
                val body = response.body?.string() ?: return@use ListingOutcome.Failed
                val array = JSONArray(body)
                ListingOutcome.Names(
                    (0 until array.length()).mapNotNull { i ->
                        val obj = array.optJSONObject(i) ?: return@mapNotNull null
                        if (obj.optString("type") != "dir") return@mapNotNull null
                        obj.optString("name").takeIf { it.isNotBlank() }
                    }
                )
            }
        }.getOrElse { failure ->
            // A dropped connection, a DNS failure, a captive portal. Not an absence either.
            Log.w(TAG, "Failed to list AutoEQ category $category", failure)
            ListingOutcome.Failed
        }
    }


    /** Fetches and parses [result]'s ParametricEQ.txt into a curve, or null on any failure. */
    suspend fun fetchCurve(result: Result): EqualizerSettings? = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(result.name, "UTF-8").replace("+", "%20")
        val url = "https://raw.githubusercontent.com/$OWNER/$REPO/master/results/oratory1990/" +
            "${result.category}/$encodedName/$encodedName%20ParametricEQ.txt"
        runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.let(::parseParametricEq)
            }
        }.onFailure { Log.w(TAG, "Failed to fetch AutoEQ curve for ${result.name}", it) }.getOrNull()
    }

    private val FILTER_LINE = Regex(
        """Filter\s+\d+:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses AutoEQ's ParametricEQ.txt format (the same one Equalizer APO/Wavelet/etc. consume) -
     * a "Preamp: X dB" line plus one "Filter N: ON <type> Fc <freq> Hz Gain <gain> dB Q <q>" line
     * per band. Unrecognized lines are just skipped rather than failing the whole parse, since a
     * comment line or a format quirk in one file shouldn't sink an otherwise-valid curve.
     */
    internal fun parseParametricEq(text: String): EqualizerSettings? {
        val bands = text.lineSequence().mapNotNull { line ->
            val match = FILTER_LINE.find(line) ?: return@mapNotNull null
            val (typeCode, freq, gain, q) = match.destructured
            val type = when (typeCode.uppercase()) {
                "LSC" -> EqualizerSettings.FilterType.LOW_SHELF
                "HSC" -> EqualizerSettings.FilterType.HIGH_SHELF
                "LP", "LPQ" -> EqualizerSettings.FilterType.LOW_PASS
                "HP", "HPQ" -> EqualizerSettings.FilterType.HIGH_PASS
                else -> EqualizerSettings.FilterType.PEAKING
            }
            EqualizerSettings.EqBand(
                freqHz = freq.toFloat().coerceIn(EqualizerSettings.MIN_FREQ_HZ, EqualizerSettings.MAX_FREQ_HZ),
                gainDb = gain.toFloat().coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB),
                q = q.toFloat().coerceIn(EqualizerSettings.MIN_Q, EqualizerSettings.MAX_Q),
                type = type,
                enabled = true,
            )
        }.toList()
        if (bands.isEmpty()) return null

        // AutoEQ's "Preamp:" line is deliberately not applied to the bands.
        //
        // It is a single global gain stage - one attenuation of the whole signal, the way Equalizer
        // APO implements it - and it exists purely to buy headroom for the boosts that follow. It
        // is not a per-band offset, and adding it to each band's gain (as this used to) does not
        // move the curve down by that amount: a peaking filter's gain only acts around its own
        // centre frequency, so shifting all of them turns every band AutoEQ specified at 0dB into
        // a real cut at its centre and leaves a comb of dips instead of a level change. Low/high
        // pass bands have no gain to offset at all, so they silently kept their original level
        // while everything around them moved - the same curve, differently wrong.
        //
        // Headroom is already handled, and handled globally, by EqualizerAudioProcessor's own
        // pre-gain, which derives exactly this kind of attenuation from how much boost the curve
        // actually asks for. So the filters are taken as written and the preamp's job is left to
        // the stage that already does it.
        return EqualizerSettings.DEFAULT.copy(enabled = true, bands = bands)
    }
}
