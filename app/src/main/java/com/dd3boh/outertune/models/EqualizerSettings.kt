/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * The in-app parametric equalizer's configuration.
 *
 * Parsed with org.json for the same reason [PlayerLayout] is: no serialization plugin needed for
 * one small, defensively-parsed file. Every field falls back to a sane default rather than
 * rejecting the whole thing, since a bad value here should never be the reason the app can't
 * play the audio it just about lets you shape.
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bands: List<EqBand> = DEFAULT_BANDS,
) {
    enum class FilterType(val key: String) {
        PEAKING("peaking"),
        LOW_SHELF("low_shelf"),
        HIGH_SHELF("high_shelf"),
        LOW_PASS("low_pass"),
        HIGH_PASS("high_pass");

        companion object {
            fun from(key: String?): FilterType = entries.firstOrNull { it.key == key } ?: PEAKING
        }
    }

    data class EqBand(
        val freqHz: Float,
        val gainDb: Float = 0f,
        val q: Float = DEFAULT_Q,
        val type: FilterType = FilterType.PEAKING,
        val enabled: Boolean = true,
    )

    /** Applies a preset's gains onto this settings' existing band frequencies/types/Qs. */
    fun withPresetGains(gains: List<Float>): EqualizerSettings {
        val newBands = bands.mapIndexed { i, band ->
            band.copy(gainDb = gains.getOrElse(i) { 0f }.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB))
        }
        return copy(bands = newBands)
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("enabled", enabled)
        val array = JSONArray()
        bands.forEach { band ->
            val obj = JSONObject()
            obj.put("freq", band.freqHz.toDouble())
            obj.put("gain", band.gainDb.toDouble())
            obj.put("q", band.q.toDouble())
            obj.put("type", band.type.key)
            obj.put("enabled", band.enabled)
            array.put(obj)
        }
        root.put("bands", array)
        return root.toString()
    }

    companion object {
        /** Bumped alongside this file's shape. A file claiming a newer version is refused. */
        const val SCHEMA_VERSION = 1

        const val MIN_GAIN_DB = -15f
        const val MAX_GAIN_DB = 15f
        const val MIN_Q = 0.1f
        const val MAX_Q = 10f
        const val DEFAULT_Q = 1f
        const val MIN_FREQ_HZ = 16f
        const val MAX_FREQ_HZ = 20000f

        /**
         * 12 bands, roughly octave-spaced at the low end and tightening toward the top, matching
         * how graphic EQs are usually laid out (finer control where hearing is most sensitive to
         * placement, without needing 20+ bands to get there).
         */
        val DEFAULT_FREQUENCIES: List<Float> =
            listOf(16f, 31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 16000f)

        val DEFAULT_BANDS: List<EqBand> = DEFAULT_FREQUENCIES.map { EqBand(freqHz = it) }

        val DEFAULT = EqualizerSettings()

        /** Gain-per-band presets, applied over whatever frequencies/types/Qs are already set. */
        val PRESETS: Map<String, List<Float>> = linkedMapOf(
            "Flat" to List(DEFAULT_FREQUENCIES.size) { 0f },
            "Bass Boost" to listOf(8f, 7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 6f, 7f, 8f),
            "Vocal" to listOf(-4f, -4f, -3f, -1f, 1f, 3f, 3f, 2f, 0f, -1f, -2f, -3f),
        )

        /**
         * @return the parsed settings, or a message explaining why the file/value was refused.
         */
        fun parse(json: String): Result<EqualizerSettings> = runCatching {
            val root = JSONObject(json)

            val version = root.optInt("schemaVersion", 1)
            require(version <= SCHEMA_VERSION) {
                "This equalizer setting was saved by a newer version (v$version)."
            }

            val array = root.optJSONArray("bands")
            val bands = if (array == null || array.length() == 0) {
                DEFAULT_BANDS
            } else {
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    EqBand(
                        freqHz = obj.optDouble("freq", 1000.0).toFloat().coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ),
                        gainDb = obj.optDouble("gain", 0.0).toFloat().coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
                        q = obj.optDouble("q", DEFAULT_Q.toDouble()).toFloat().coerceIn(MIN_Q, MAX_Q),
                        type = FilterType.from(obj.optString("type")),
                        enabled = obj.optBoolean("enabled", true),
                    )
                }.ifEmpty { DEFAULT_BANDS }
            }

            EqualizerSettings(
                enabled = root.optBoolean("enabled", false),
                bands = bands,
            )
        }
    }
}
