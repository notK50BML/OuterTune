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
 */
object AutoEqRepository {
    private const val TAG = "AutoEqRepository"
    private const val OWNER = "jaakkopasanen"
    private const val REPO = "AutoEq"
    private val CATEGORIES = listOf("over-ear", "in-ear", "earbud")

    data class Result(val name: String, val category: String)

    private val httpClient = OkHttpClient()

    /** Case-insensitive substring match against oratory1990's headphone folder names. */
    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        CATEGORIES.flatMap { category ->
            runCatching { listNames(category) }
                .onFailure { Log.w(TAG, "Failed to list AutoEQ category $category", it) }
                .getOrDefault(emptyList())
                .filter { it.contains(query, ignoreCase = true) }
                .map { Result(it, category) }
        }.sortedBy { it.name }
    }

    private fun listNames(category: String): List<String> {
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/results/oratory1990/$category"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val array = JSONArray(body)
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                if (obj.optString("type") != "dir") return@mapNotNull null
                obj.optString("name").takeIf { it.isNotBlank() }
            }
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

    private val PREAMP_LINE = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
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

        // AutoEQ's preamp offsets the whole curve down so the combined boost/cut never clips -
        // this app's band model has no separate preamp field, so the closest equivalent is baking
        // that same offset into every band's own gain.
        val preampDb = PREAMP_LINE.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val adjustedBands = if (preampDb == 0f) bands else bands.map {
            it.copy(gainDb = (it.gainDb + preampDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB))
        }

        return EqualizerSettings.DEFAULT.copy(enabled = true, bands = adjustedBands)
    }
}
