/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Headphone correction curves from AutoEQ's public results database.
 *
 * https://github.com/jaakkopasanen/AutoEq, MIT licensed, scoped to oratory1990's measurements - the
 * most consistently referenced source in that database, and one whose directory layout (one folder
 * per headphone under over-ear/in-ear/earbud) is simple enough to query against GitHub's own API
 * rather than needing a bundled or self-hosted index.
 *
 * The full database spans about twenty other measurement sources and several thousand entries;
 * mirroring all of it would mean shipping a large dataset or running infrastructure to serve it.
 * Querying the directory listing live means nothing bundled to go stale and results that are exactly
 * what is in the repository right now - at the cost of covering one measurement source rather than
 * all of them, and not working offline.
 *
 * A port of the Android app's `utils/AutoEqRepository.kt`. The parser is deliberately identical, so
 * a curve loaded on the phone and the same curve loaded here are the same numbers; only the HTTP
 * client and the JSON parser differ, because this module has ktor and kotlinx.serialization where
 * the Android one has OkHttp and org.json.
 */
object AutoEq {

    private const val OWNER = "jaakkopasanen"
    private const val REPO = "AutoEq"
    private val CATEGORIES = listOf("over-ear", "in-ear", "earbud")

    data class Headphone(val name: String, val category: String)

    private val client by lazy { HttpClient(OkHttp) }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Directory listings, kept for the session.
     *
     * Each category is a single request returning a few hundred names, and search runs on every
     * keystroke - without this, typing a headphone's name would issue three requests per character
     * and get rate-limited by GitHub within a word.
     */
    private val listings = mutableMapOf<String, List<String>>()

    /** Case-insensitive substring match against oratory1990's folder names. */
    suspend fun search(query: String): List<Headphone> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        CATEGORIES.flatMap { category ->
            runCatching { listNames(category) }
                .getOrDefault(emptyList())
                .filter { it.contains(query, ignoreCase = true) }
                .map { Headphone(it, category) }
        }.sortedBy { it.name }
    }

    private suspend fun listNames(category: String): List<String> {
        listings[category]?.let { return it }
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/results/oratory1990/$category"
        val response = client.get(url) { header("Accept", "application/vnd.github+json") }
        if (!response.status.isSuccess()) return emptyList()

        val names = json.parseToJsonElement(response.bodyAsText()).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "dir") return@mapNotNull null
            obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
        listings[category] = names
        return names
    }

    /** Fetches and parses [headphone]'s ParametricEQ.txt into a band list, or null on any failure. */
    suspend fun fetchCurve(headphone: Headphone): List<EqBand>? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(headphone.name, "UTF-8").replace("+", "%20")
        val url = "https://raw.githubusercontent.com/$OWNER/$REPO/master/results/oratory1990/" +
            "${headphone.category}/$encoded/$encoded%20ParametricEQ.txt"
        runCatching {
            val response = client.get(url)
            if (!response.status.isSuccess()) return@runCatching null
            parseParametricEq(response.bodyAsText())
        }.getOrNull()
    }

    private val FILTER_LINE = Regex(
        """Filter\s+\d+:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses AutoEQ's ParametricEQ.txt - the same format Equalizer APO and Wavelet consume.
     *
     * A "Preamp: X dB" line followed by one "Filter N: ON <type> Fc <freq> Hz Gain <gain> dB Q <q>"
     * per band. Unrecognised lines are skipped rather than failing the parse, since a comment or a
     * format quirk in one file should not sink an otherwise valid curve.
     *
     * The preamp line is deliberately not applied. It is a single global attenuation - one gain
     * stage, the way Equalizer APO implements it - existing to buy headroom for the boosts that
     * follow. Folding it into each band's gain does not move the curve down by that amount: a
     * peaking filter's gain only acts around its own centre, so shifting every band turns each one
     * AutoEQ specified at 0dB into a real cut and leaves a comb of dips instead of a level change.
     * This is a mistake the Android side made and corrected; the comment is here so it is not made
     * again on this side.
     */
    internal fun parseParametricEq(text: String): List<EqBand>? {
        val bands = text.lineSequence().mapNotNull { line ->
            val match = FILTER_LINE.find(line) ?: return@mapNotNull null
            val (typeCode, freq, gain, q) = match.destructured
            val type = when (typeCode.uppercase()) {
                "LSC" -> EqBandType.LOW_SHELF
                "HSC" -> EqBandType.HIGH_SHELF
                // Low and high pass sections appear in a handful of files. They have no gain to
                // apply and this bank has no way to express them, so they are dropped rather than
                // silently reinterpreted as a peaking filter, which would put a bump where the file
                // asked for a rolloff.
                "LP", "LPQ", "HP", "HPQ" -> return@mapNotNull null
                else -> EqBandType.PEAKING
            }
            EqBand(
                freqHz = freq.toFloat().coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ),
                gainDb = gain.toFloat().coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
                q = q.toFloat().coerceIn(MIN_Q, MAX_Q),
                type = type,
            )
        }.toList()
        return bands.ifEmpty { null }
    }

    const val MIN_FREQ_HZ = 20f
    const val MAX_FREQ_HZ = 20_000f
    const val MIN_GAIN_DB = -24f
    const val MAX_GAIN_DB = 24f
    const val MIN_Q = 0.1f
    const val MAX_Q = 20f
}
