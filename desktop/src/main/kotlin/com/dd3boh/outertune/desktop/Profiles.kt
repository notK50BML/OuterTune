/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Shareable settings profiles - a theme, an equaliser, a whole setup - without anything personal:
 * no stats, history, likes, playlists, accounts, folders or tokens. Ever.
 *
 * The same file format on the phone and here (Android: utils/SettingsProfiles.kt). Keep them in step.
 *
 *     {
 *       "outertuneProfile": 1,
 *       "name": "Night owl",
 *       "createdOn": "desktop" | "android",
 *       "createdAt": "2026-10-18T10:00:00Z",
 *       "categories": {
 *         "appearance": { "common": { ... }, "desktop": { "<key>": "<stored text>" } },
 *         "player": ..., "equaliser": ..., "lyrics": ..., "playback": ...
 *       }
 *     }
 *
 * Each category carries:
 *  - `common`: the settings both apps have, under shared names and plain JSON values - what lets a
 *    profile made on the phone set up the desktop, and the other way round.
 *  - the creating app's own block (`desktop` / `android`): every setting of that category exactly as
 *    stored, so a profile restores completely on the app that made it.
 *
 * Importing uses this app's own block when there is one and `common` otherwise. Anything unknown is
 * ignored, so either app can add settings without breaking the other's files.
 */
object Profiles {
    const val FORMAT = 1
    const val EXTENSION = "otprofile"
    private const val PLATFORM = "desktop"

    enum class Category(val key: String, val label: String, val description: String) {
        Appearance("appearance", "Appearance", "Theme, colours, fonts, player background"),
        Player("player", "Player", "Player buttons and layout, sleep timer"),
        Equaliser("equaliser", "Equaliser", "Curve, compressor, balance and saved EQ profiles"),
        Lyrics("lyrics", "Lyrics", "Text size, alignment, word sync, sources"),
        Playback("playback", "Playback", "Normalisation, queue, content language, Listen Together offset"),
    }

    /** Which of this app's settings belong to which category. Personal ones are in none. */
    private fun categoryOf(key: String): Category? = when {
        key.startsWith("ui.") -> Category.Appearance
        key in setOf("player.seekButtons", "player.visualizer", "player.buttons", "player.queueTitle", "player.queueHandleTop", "player.layout",
            "window.sideQueue", "sleep.fade", "sleep.defaultMinutes") || key.startsWith("keys.") -> Category.Player
        key.startsWith("eq.") -> Category.Equaliser
        key.startsWith("lyrics.") -> Category.Lyrics
        key in setOf("player.persistentQueue", "player.normalise", "player.skipOnError", "player.minPlayPercent",
            "content.language", "content.country", "content.hideExplicit", "listentogether.offsetMs") -> Category.Playback
        // player.savedQueue, search.*, local.folders, discord.*, library.history: personal.
        else -> null
    }

    data class Profile(val name: String, val createdOn: String, val categories: Map<Category, JsonObject>)

    fun export(settings: Settings, name: String, chosen: Set<Category>): String {
        val json = buildJsonObject {
            put("outertuneProfile", FORMAT)
            put("name", name)
            put("createdOn", PLATFORM)
            put("createdAt", Instant.now().toString())
            put("categories", buildJsonObject {
                for (category in chosen) {
                    put(category.key, buildJsonObject {
                        put("common", common(settings, category))
                        put(PLATFORM, buildJsonObject {
                            settings.keys.filter { categoryOf(it) == category }.forEach { key ->
                                settings.raw(key)?.let { put(key, it) }
                            }
                        })
                    })
                }
            })
        }
        return PrettyJson.encode(json)
    }

    fun parse(text: String): Profile? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        if ((root["outertuneProfile"]?.jsonPrimitive?.intOrNull ?: return null) > FORMAT) return null
        val categories = root["categories"]?.jsonObject ?: return null
        Profile(
            name = root["name"]?.jsonPrimitive?.contentOrNull ?: "Profile",
            createdOn = root["createdOn"]?.jsonPrimitive?.contentOrNull ?: "",
            categories = Category.entries.mapNotNull { c -> categories[c.key]?.jsonObject?.let { c to it } }.toMap(),
        )
    }.getOrNull()

    /** Applies [chosen] from [profile]; returns how many settings changed. */
    fun apply(settings: Settings, profile: Profile, chosen: Set<Category>): Int {
        var applied = 0
        for ((category, block) in profile.categories) {
            if (category !in chosen) continue
            val own = block[PLATFORM]?.jsonObject
            if (own != null) {
                for ((key, value) in own) {
                    if (categoryOf(key) != category) continue
                    val text = (value as? JsonPrimitive)?.contentOrNull ?: continue
                    settings.putRaw(key, text)
                    applied++
                }
            } else {
                block["common"]?.jsonObject?.let { applied += applyCommon(settings, category, it) }
            }
        }
        return applied
    }

    // ---- the shared names --------------------------------------------------------------------

    private fun common(s: Settings, category: Category): JsonObject = buildJsonObject {
        when (category) {
            Category.Appearance -> {
                put("themeMode", s.themeMode.name.lowercase())
                put("pureBlack", s.pureBlack)
                put("dynamicTheme", s.dynamicTheme)
                put("sliderStyle", s.sliderStyle.name.lowercase())
                // The phone's tab letters; Stats (T) is the desktop's own and stays behind.
                put("tabs", NavTab.encode(NavTab.decode(s.tabs)).filter { it != 'T' })
            }
            Category.Player -> {
                put("buttons", s.playerButtons)
                put("showQueueTitle", s.showQueueTitle)
                put("queueHandleTop", s.queueHandleTop)
                put("sleepFade", s.sleepFade)
                put("sleepDefaultMinutes", s.sleepDefaultMinutes)
            }
            Category.Equaliser -> {
                val eq = s.equalizerStore()
                put("enabled", eq.enabled)
                put("balance", eq.balance)
                put("bands", buildJsonArray {
                    eq.bands.forEach { b ->
                        add(buildJsonObject {
                            put("freqHz", b.freqHz)
                            put("gainDb", b.gainDb)
                            put("q", b.q)
                            put("type", b.type.name.lowercase())
                        })
                    }
                })
                eq.compressor?.let { c ->
                    put("compressor", buildJsonObject {
                        put("enabled", c.enabled)
                        put("thresholdDb", c.thresholdDb)
                        put("ratio", c.ratio)
                        put("attackMs", c.attackMs)
                        put("releaseMs", c.releaseMs)
                        put("makeupGainDb", c.makeupGainDb)
                    })
                }
            }
            Category.Lyrics -> {
                put("textScalePercent", s.lyricsTextScale)
                put("alignment", if (s.lyricsAlignment == LyricsAlignment.Left) "left" else "center")
                put("wordByWord", s.wordByWordLyrics)
                put("estimateWordTimings", s.estimateWordTimings)
                put("highlightStyle", if (s.lyricsHighlight == LyricsHighlight.Sweep) "sweep" else "fade")
                put("showLyricsType", s.lyricsShowKind)
                // No "offsetMs" any more: the lyrics timing offset is each song's own now, kept with
                // the library rather than the settings, so a profile has none to carry.
                put("openOnCoverClick", s.lyricsOnCoverClick)
                put("lrclib", s.lyricsLrclib)
                put("kugou", s.lyricsKugou)
            }
            Category.Playback -> {
                put("normalise", s.audioNormalisation)
                put("skipOnError", s.skipOnError)
                put("minPlayPercent", s.minPlayPercent)
                put("persistentQueue", s.persistentQueue)
                put("contentLanguage", s.contentLanguage)
                put("contentCountry", s.contentCountry)
                put("listenTogetherOffsetMs", s.listenTogetherOffsetMs)
            }
        }
    }

    private fun applyCommon(s: Settings, category: Category, c: JsonObject): Int {
        var n = 0
        fun bool(k: String, set: (Boolean) -> Unit) { c[k]?.jsonPrimitive?.booleanOrNull?.let { set(it); n++ } }
        fun int(k: String, set: (Int) -> Unit) { c[k]?.jsonPrimitive?.intOrNull?.let { set(it); n++ } }
        fun str(k: String, set: (String) -> Unit) { (c[k] as? JsonPrimitive)?.contentOrNull?.let { set(it); n++ } }
        when (category) {
            Category.Appearance -> {
                str("themeMode") { v -> ThemeMode.entries.firstOrNull { it.name.equals(v, true) }?.let { s.themeMode = it } }
                bool("pureBlack") { s.pureBlack = it }
                bool("dynamicTheme") { s.dynamicTheme = it }
                str("tabs") { v -> NavTab.decode(v).takeIf { v.isNotBlank() }?.let { s.tabs = NavTab.encode(it) } }
                str("sliderStyle") { v -> SeekBarStyle.entries.firstOrNull { it.name.equals(v, true) }?.let { s.sliderStyle = it } }
            }
            Category.Player -> {
                str("buttons") { s.playerButtons = PlayerButtonKind.encode(PlayerButtonKind.decode(it)) }
                bool("showQueueTitle") { s.showQueueTitle = it }
                bool("queueHandleTop") { s.queueHandleTop = it }
                bool("sleepFade") { s.sleepFade = it }
                int("sleepDefaultMinutes") { s.sleepDefaultMinutes = it }
            }
            Category.Equaliser -> {
                val eq = s.equalizerStore()
                bool("enabled") { eq.enabled = it }
                c["balance"]?.jsonPrimitive?.floatOrNull?.let { eq.balance = it.coerceIn(-1f, 1f); n++ }
                (c["bands"] as? JsonArray)?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val freq = o["freqHz"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                    val gain = o["gainDb"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val q = o["q"]?.jsonPrimitive?.floatOrNull ?: 1f
                    val type = when (o["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                        "low_shelf", "lowshelf" -> EqBandType.LOW_SHELF
                        "high_shelf", "highshelf" -> EqBandType.HIGH_SHELF
                        else -> EqBandType.PEAKING
                    }
                    if (!freq.isFinite() || freq <= 0f || !gain.isFinite() || !q.isFinite() || q <= 0f) null
                    else EqBand(freq, gain.coerceIn(-24f, 24f), q, type)
                }?.takeIf { it.isNotEmpty() }?.let { eq.bands = it; n++ }
                (c["compressor"] as? JsonObject)?.let { o ->
                    fun f(k: String, d: Float) = o[k]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: d
                    eq.compressor = CompressorPrefs(
                        enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        thresholdDb = f("thresholdDb", -18f),
                        ratio = f("ratio", 4f),
                        attackMs = f("attackMs", 20f),
                        releaseMs = f("releaseMs", 150f),
                        makeupGainDb = f("makeupGainDb", 0f),
                    )
                    n++
                }
            }
            Category.Lyrics -> {
                int("textScalePercent") { s.lyricsTextScale = it.coerceIn(50, 300) }
                str("alignment") { s.lyricsAlignment = if (it == "left") LyricsAlignment.Left else LyricsAlignment.Centre }
                bool("wordByWord") { s.wordByWordLyrics = it }
                bool("estimateWordTimings") { s.estimateWordTimings = it }
                str("highlightStyle") { s.lyricsHighlight = if (it == "sweep") LyricsHighlight.Sweep else LyricsHighlight.Fade }
                bool("showLyricsType") { s.lyricsShowKind = it }
                // "offsetMs" in an older profile, from either app, is passed over: there is no
                // single offset left to apply it to.
                bool("openOnCoverClick") { s.lyricsOnCoverClick = it }
                bool("lrclib") { s.lyricsLrclib = it }
                bool("kugou") { s.lyricsKugou = it }
            }
            Category.Playback -> {
                bool("normalise") { s.audioNormalisation = it }
                bool("skipOnError") { s.skipOnError = it }
                int("minPlayPercent") { s.minPlayPercent = it.coerceIn(0, 100) }
                bool("persistentQueue") { s.persistentQueue = it }
                str("contentLanguage") { s.contentLanguage = it }
                str("contentCountry") { s.contentCountry = it }
                int("listenTogetherOffsetMs") { s.listenTogetherOffsetMs = it.coerceIn(-3000, 3000) }
            }
        }
        return n
    }

    /** Indented JSON, so a profile can be read and hand-edited. */
    private object PrettyJson {
        private val json = kotlinx.serialization.json.Json { prettyPrint = true }
        fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)
    }
}
