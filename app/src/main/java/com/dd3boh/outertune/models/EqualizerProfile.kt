/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named, savable snapshot of the equalizer's bands/balance/compressor - what tapping a preset
 * chip loads and what "Save" writes back to.
 *
 * Without this, a preset was just a hardcoded gain curve: tapping "Rock" then nudging a band
 * had nowhere to persist that nudge, so the next time "Rock" was tapped the tweak was gone -
 * indistinguishable from a bug even though it was always the intended behaviour. Every preset
 * now doubles as a profile name; [EqualizerSettings.PRESETS] supplies its factory default
 * ([factoryDefault]), and an explicit Save writes a custom override into the persisted list this
 * class serializes. A user-created name has no factory default at all - only a saved value.
 */
data class EqualizerProfile(
    val name: String,
    val settings: EqualizerSettings,
) {
    companion object {
        private const val SCHEMA_VERSION = 1

        /** The built-in preset's un-customized curve, or null for a purely user-created name. */
        fun factoryDefault(name: String): EqualizerProfile? {
            val gains = EqualizerSettings.PRESETS[name] ?: return null
            return EqualizerProfile(
                name = name,
                settings = EqualizerSettings.DEFAULT.withPresetGains(gains),
            )
        }

        fun listToJson(profiles: List<EqualizerProfile>): String {
            val root = JSONObject()
            root.put("schemaVersion", SCHEMA_VERSION)
            val array = JSONArray()
            profiles.forEach { profile ->
                val obj = JSONObject()
                obj.put("name", profile.name)
                obj.put("settings", JSONObject(profile.settings.toJson()))
                array.put(obj)
            }
            root.put("profiles", array)
            return root.toString()
        }

        /** Never throws - a corrupt or newer-schema file just means no saved overrides yet. */
        fun listFromJson(json: String): List<EqualizerProfile> = runCatching {
            if (json.isBlank()) return emptyList()
            val root = JSONObject(json)
            val version = root.optInt("schemaVersion", 1)
            if (version > SCHEMA_VERSION) return emptyList()

            val array = root.optJSONArray("profiles") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val settingsObj = obj.optJSONObject("settings") ?: return@mapNotNull null
                val settings = EqualizerSettings.parse(settingsObj.toString()).getOrNull() ?: return@mapNotNull null
                EqualizerProfile(name = name, settings = settings)
            }
        }.getOrDefault(emptyList())
    }
}
