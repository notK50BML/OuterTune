/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SliderStyleKey
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CustomThemeColorKey
import com.dd3boh.outertune.constants.CustomThemeKey
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DynamicThemeKey
import com.dd3boh.outertune.constants.EnableKugouKey
import com.dd3boh.outertune.constants.EnableLrcLibKey
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.ListenTogetherOffsetKey
import com.dd3boh.outertune.constants.LyricEstimatedWordSync
import com.dd3boh.outertune.constants.LyricFontSizeKey
import com.dd3boh.outertune.constants.LyricKaraokeEnable
import com.dd3boh.outertune.constants.LyricOffsetKey
import com.dd3boh.outertune.constants.LyricsTextPositionKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerButtonsKey
import com.dd3boh.outertune.constants.PureBlackKey
import com.dd3boh.outertune.constants.ShowLyricsOnClickKey
import com.dd3boh.outertune.constants.ShowQueueTitleKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SleepTimerDefaultMinutesKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.player.PlayerButton
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Shareable settings profiles - a theme, an equaliser, a whole setup - without anything personal:
 * no stats, history, likes, playlists, accounts, folders, tokens or sync state. Ever.
 *
 * The same file format as the desktop app (desktop: Profiles.kt), so a profile made on either opens
 * on the other. Keep them in step.
 *
 * Each category carries `common` - the settings both apps have, under shared names - and the
 * creating app's own block (`android` here), every setting of that category exactly as stored. An
 * import uses this app's own block when there is one, and `common` otherwise.
 */
object SettingsProfiles {
    const val FORMAT = 1
    const val EXTENSION = "otprofile"
    private const val PLATFORM = "android"

    enum class Category(val key: String, @StringRes val title: Int, @StringRes val description: Int, val keys: Set<String>) {
        APPEARANCE(
            "appearance", R.string.profile_cat_appearance, R.string.profile_cat_appearance_desc,
            setOf(
                "dynamicTheme", "customTheme", "customThemeColor", "playerBackgroundStyle", "playerAutoTextContrast",
                "liquidAudioReactive", "liquidColorScheme", "liquidShapeStyle", "liquidTextContrast",
                "liquidChromaticShock", "liquidFerrofluidGpu", "liquidFerrofluidQuality", "liquidFerrofluidReactivity",
                "showTopBarLogo", "playerLayout", "darkMode", "pureBlack", "showLikedAndDownloadedPlaylist",
                "swipeToQueue", "tabletUi", "enabledTabs", "enabledFilters", "defaultOpenTab", "slimNavBar",
                "sliderStyle", "hideSettingsDescriptions", "artworkFallbackToLowRes", "flatSubfolders",
                "recognitionButtonPlacement",
            ),
        ),
        PLAYER(
            "player", R.string.profile_cat_player, R.string.profile_cat_player_desc,
            setOf(
                "playerButtons", "showQueueTitle", "showEqualizerButton", "showEqualizerHandle", "seekIncrement",
                "swipeToSkip", "sleepTimerFade", "sleepTimerFadeDuration", "sleepTimerDefaultMinutes",
                "sleepTimerShowOnPlayer", "showQueuesBesideCurrent", "maxQueues", "lockQueue",
            ),
        ),
        EQUALISER(
            "equaliser", R.string.profile_cat_equaliser, R.string.profile_cat_equaliser_desc,
            setOf(
                "equalizerSettings", "equalizerProfiles", "eqShowPanelSettings", "eqAutoSaveProfiles",
                "eqHeadroom", "eqUseDials", "eqValueColorGradient", "audioUpscaling",
            ),
        ),
        LYRICS(
            "lyrics", R.string.profile_cat_lyrics, R.string.profile_cat_lyrics_desc,
            setOf(
                "showLyrics", "showLyricsOnClick", "lyricsTextPosition", "multilineLrc", "lyricTrim",
                "preferLocalLyrics", "lyricFontSize", "lyricClickable", "lyricKaraokeEnable",
                "lyricEstimatedWordSync", "lyricOffset", "lyricUpdateSpeed", "enableLyricsPrefetch",
                "lyricsPrefetchCount", "lyricsFetchMode", "lyricsProviderOrder", "enableKugou", "enableLrcLib",
                "enableBetterLyrics", "enableSimpMusic", "lyricsRefreshInterval",
            ),
        ),
        PLAYBACK(
            "playback", R.string.profile_cat_playback, R.string.profile_cat_playback_desc,
            setOf(
                "audioQuality", "audioDecoder", "enableOffload", "enableGaplessOffload", "persistentQueue",
                "skipSilence", "skipOnError", "audioNormalization", "ignoreAudioFocus", "autoLoadMore",
                "keepAlive", "stopMusicOnTaskClear", "minPlaybackDur", "crossfade", "crossfadeDuration",
                "enableStreamPrecache", "contentLanguage", "contentCountry", "showArtistVideosAsSongs",
                "listenTogetherOffset",
            ),
        ),
    }

    data class Profile(val name: String, val createdOn: String, val categories: Map<Category, JSONObject>)

    suspend fun export(store: DataStore<Preferences>, name: String, chosen: Set<Category>): String {
        val prefs = store.data.first()
        val categories = JSONObject()
        for (category in chosen) {
            val own = JSONObject()
            prefs.asMap().forEach { (key, value) ->
                if (key.name in category.keys) typed(value)?.let { own.put(key.name, it) }
            }
            categories.put(category.key, JSONObject().put("common", common(prefs, category)).put(PLATFORM, own))
        }
        return JSONObject()
            .put("outertuneProfile", FORMAT)
            .put("name", name)
            .put("createdOn", PLATFORM)
            .put("createdAt", Instant.now().toString())
            .put("categories", categories)
            .toString(2)
    }

    fun parse(text: String): Profile? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("outertuneProfile", Int.MAX_VALUE) > FORMAT) return null
        val categories = root.getJSONObject("categories")
        Profile(
            name = root.optString("name", "Profile"),
            createdOn = root.optString("createdOn", ""),
            categories = Category.entries.mapNotNull { c -> categories.optJSONObject(c.key)?.let { c to it } }.toMap(),
        )
    }.getOrNull()

    /** Applies [chosen] from [profile]; returns how many settings changed. */
    suspend fun apply(store: DataStore<Preferences>, profile: Profile, chosen: Set<Category>): Int {
        var applied = 0
        store.edit { prefs ->
            for ((category, block) in profile.categories) {
                if (category !in chosen) continue
                val own = block.optJSONObject(PLATFORM)
                if (own != null) {
                    own.keys().forEach { name ->
                        if (name in category.keys && untyped(prefs, name, own.optJSONObject(name))) applied++
                    }
                } else {
                    block.optJSONObject("common")?.let { applied += applyCommon(prefs, category, it) }
                }
            }
        }
        return applied
    }

    // ---- this app's own values, with their types -----------------------------------------------

    private fun typed(value: Any): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("b", value)
        is Int -> JSONObject().put("i", value)
        is Long -> JSONObject().put("l", value)
        is Float -> JSONObject().put("f", value.toDouble())
        is String -> JSONObject().put("s", value)
        is Set<*> -> JSONObject().put("ss", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun untyped(prefs: MutablePreferences, name: String, v: JSONObject?): Boolean {
        v ?: return false
        when {
            v.has("b") -> prefs[booleanPreferencesKey(name)] = v.getBoolean("b")
            v.has("i") -> prefs[intPreferencesKey(name)] = v.getInt("i")
            v.has("l") -> prefs[longPreferencesKey(name)] = v.getLong("l")
            v.has("f") -> prefs[floatPreferencesKey(name)] = v.getDouble("f").toFloat()
            v.has("s") -> prefs[stringPreferencesKey(name)] = v.getString("s")
            v.has("ss") -> {
                val array = v.getJSONArray("ss")
                prefs[stringSetPreferencesKey(name)] = (0 until array.length()).map { array.getString(it) }.toSet()
            }
            else -> return false
        }
        return true
    }

    // ---- the shared names - see the desktop's Profiles.kt -------------------------------------

    private fun common(p: Preferences, category: Category): JSONObject = JSONObject().apply {
        when (category) {
            Category.APPEARANCE -> {
                put("themeMode", when (p[DarkModeKey]) { "ON" -> "dark"; "OFF" -> "light"; else -> "system" })
                put("pureBlack", p[PureBlackKey] ?: false)
                put("dynamicTheme", p[DynamicThemeKey] ?: true)
                put("sliderStyle", (p[SliderStyleKey] ?: "SQUIGGLY").lowercase())
                val custom = p[CustomThemeKey] ?: false
                put("accentColor", if (custom) p[CustomThemeColorKey]?.let { String.format("#%06X", 0xFFFFFF and it) } else JSONObject.NULL)
            }
            Category.PLAYER -> {
                put("buttons", p[PlayerButtonsKey] ?: "")
                put("showQueueTitle", p[ShowQueueTitleKey] ?: true)
                put("sleepFade", p[SleepTimerFadeKey] ?: SleepTimerDefaults.FADE_ENABLED)
                put("sleepDefaultMinutes", p[SleepTimerDefaultMinutesKey] ?: SleepTimerDefaults.DEFAULT_MINUTES)
            }
            Category.EQUALISER -> {
                val eq = p[EqualizerSettingsKey]?.let { EqualizerSettings.parse(it).getOrNull() } ?: EqualizerSettings()
                put("enabled", eq.enabled)
                put("balance", eq.balance.toDouble())
                put("bassShelfDb", eq.bassShelfDb.toDouble())
                put("trebleShelfDb", eq.trebleShelfDb.toDouble())
                put("bands", JSONArray().apply {
                    eq.bands.filter { it.enabled }.forEach { b ->
                        put(JSONObject()
                            .put("freqHz", b.freqHz.toDouble())
                            .put("gainDb", b.gainDb.toDouble())
                            .put("q", b.q.toDouble())
                            .put("type", b.type.key))
                    }
                })
                put("compressor", JSONObject()
                    .put("enabled", eq.compressor.enabled)
                    .put("thresholdDb", eq.compressor.thresholdDb.toDouble())
                    .put("ratio", eq.compressor.ratio.toDouble())
                    .put("attackMs", eq.compressor.attackMs.toDouble())
                    .put("releaseMs", eq.compressor.releaseMs.toDouble())
                    .put("makeupGainDb", eq.compressor.makeupGainDb.toDouble()))
            }
            Category.LYRICS -> {
                // The desktop's lyrics text is a percentage of 20sp, the phone's default size.
                put("textScalePercent", (p[LyricFontSizeKey] ?: 20) * 5)
                put("alignment", when (p[LyricsTextPositionKey]) { "LEFT" -> "left"; "RIGHT" -> "right"; else -> "center" })
                put("wordByWord", p[LyricKaraokeEnable] ?: false)
                put("estimateWordTimings", p[LyricEstimatedWordSync] ?: false)
                put("offsetMs", p[LyricOffsetKey] ?: 0)
                put("openOnCoverClick", p[ShowLyricsOnClickKey] ?: true)
                put("lrclib", p[EnableLrcLibKey] ?: true)
                put("kugou", p[EnableKugouKey] ?: true)
            }
            Category.PLAYBACK -> {
                put("normalise", p[AudioNormalizationKey] ?: true)
                put("skipOnError", p[SkipOnErrorKey] ?: false)
                put("minPlayPercent", p[minPlaybackDurKey] ?: 30)
                put("persistentQueue", p[PersistentQueueKey] ?: true)
                p[ContentLanguageKey]?.takeIf { it != "system" }?.let { put("contentLanguage", it) }
                p[ContentCountryKey]?.takeIf { it != "system" }?.let { put("contentCountry", it) }
                put("listenTogetherOffsetMs", p[ListenTogetherOffsetKey] ?: 0)
            }
        }
    }

    private fun applyCommon(p: MutablePreferences, category: Category, c: JSONObject): Int {
        var n = 0
        fun bool(k: String, key: Preferences.Key<Boolean>) { if (c.has(k) && !c.isNull(k)) { p[key] = c.getBoolean(k); n++ } }
        fun int(k: String, key: Preferences.Key<Int>, map: (Int) -> Int = { it }) { if (c.has(k) && !c.isNull(k)) { p[key] = map(c.getInt(k)); n++ } }
        fun str(k: String, set: (String) -> Unit) { if (c.has(k) && !c.isNull(k)) { set(c.getString(k)); n++ } }
        when (category) {
            Category.APPEARANCE -> {
                str("themeMode") { p[DarkModeKey] = when (it) { "dark" -> "ON"; "light" -> "OFF"; else -> "AUTO" } }
                bool("pureBlack", PureBlackKey)
                bool("dynamicTheme", DynamicThemeKey)
                str("sliderStyle") { v -> if (v.uppercase() in setOf("SQUIGGLY", "DEFAULT", "SLIM")) p[SliderStyleKey] = v.uppercase() }
                if (c.has("accentColor")) {
                    if (c.isNull("accentColor")) {
                        p[CustomThemeKey] = false
                    } else {
                        runCatching { android.graphics.Color.parseColor(c.getString("accentColor")) }.getOrNull()?.let {
                            p[CustomThemeKey] = true
                            p[CustomThemeColorKey] = it or 0xFF000000.toInt()
                        }
                    }
                    n++
                }
            }
            Category.PLAYER -> {
                str("buttons") { p[PlayerButtonsKey] = PlayerButton.encode(PlayerButton.decode(it)) }
                bool("showQueueTitle", ShowQueueTitleKey)
                bool("sleepFade", SleepTimerFadeKey)
                int("sleepDefaultMinutes", SleepTimerDefaultMinutesKey)
            }
            Category.EQUALISER -> {
                val current = p[EqualizerSettingsKey]?.let { EqualizerSettings.parse(it).getOrNull() } ?: EqualizerSettings()
                val bands = c.optJSONArray("bands")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val freq = o.optDouble("freqHz", Double.NaN).toFloat()
                        val q = o.optDouble("q", 1.0).toFloat()
                        if (!freq.isFinite() || freq <= 0f || !q.isFinite() || q <= 0f) return@mapNotNull null
                        EqualizerSettings.EqBand(
                            freqHz = freq.coerceIn(EqualizerSettings.MIN_FREQ_HZ, EqualizerSettings.MAX_FREQ_HZ),
                            gainDb = o.optDouble("gainDb", 0.0).toFloat()
                                .coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB),
                            q = q.coerceIn(EqualizerSettings.MIN_Q, EqualizerSettings.MAX_Q),
                            type = EqualizerSettings.FilterType.entries
                                .firstOrNull { it.key == o.optString("type").lowercase() }
                                ?: EqualizerSettings.FilterType.PEAKING,
                        )
                    }
                }?.takeIf { it.isNotEmpty() }
                val comp = c.optJSONObject("compressor")?.let { o ->
                    EqualizerSettings.CompressorSettings(
                        enabled = o.optBoolean("enabled", false),
                        thresholdDb = o.optDouble("thresholdDb", -18.0).toFloat(),
                        ratio = o.optDouble("ratio", 4.0).toFloat(),
                        attackMs = o.optDouble("attackMs", 20.0).toFloat(),
                        releaseMs = o.optDouble("releaseMs", 150.0).toFloat(),
                        makeupGainDb = o.optDouble("makeupGainDb", 0.0).toFloat(),
                    )
                }
                val next = current.copy(
                    enabled = if (c.has("enabled")) c.getBoolean("enabled") else current.enabled,
                    balance = c.optDouble("balance", current.balance.toDouble()).toFloat().coerceIn(-1f, 1f),
                    bassShelfDb = c.optDouble("bassShelfDb", current.bassShelfDb.toDouble()).toFloat(),
                    trebleShelfDb = c.optDouble("trebleShelfDb", current.trebleShelfDb.toDouble()).toFloat(),
                    bands = bands ?: current.bands,
                    compressor = comp ?: current.compressor,
                )
                p[EqualizerSettingsKey] = next.toJson()
                n++
            }
            Category.LYRICS -> {
                int("textScalePercent", LyricFontSizeKey) { (it / 5).coerceIn(8, 60) }
                str("alignment") { p[LyricsTextPositionKey] = when (it) { "left" -> "LEFT"; "right" -> "RIGHT"; else -> "CENTER" } }
                bool("wordByWord", LyricKaraokeEnable)
                bool("estimateWordTimings", LyricEstimatedWordSync)
                int("offsetMs", LyricOffsetKey)
                bool("openOnCoverClick", ShowLyricsOnClickKey)
                bool("lrclib", EnableLrcLibKey)
                bool("kugou", EnableKugouKey)
            }
            Category.PLAYBACK -> {
                bool("normalise", AudioNormalizationKey)
                bool("skipOnError", SkipOnErrorKey)
                int("minPlayPercent", minPlaybackDurKey) { it.coerceIn(0, 100) }
                bool("persistentQueue", PersistentQueueKey)
                str("contentLanguage") { p[ContentLanguageKey] = it }
                str("contentCountry") { p[ContentCountryKey] = it }
                int("listenTogetherOffsetMs", ListenTogetherOffsetKey) { it.coerceIn(-3000, 3000) }
            }
        }
        return n
    }
}
