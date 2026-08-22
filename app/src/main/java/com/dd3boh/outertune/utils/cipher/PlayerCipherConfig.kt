/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.cipher

import android.util.Log
import com.dd3boh.outertune.App
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Signature deobfuscation config for one player.js, looked up by its hash and used by [CipherWebView].
 *
 * @property sigFuncName name of the deobfuscation function
 * @property sigConstantArgs constants placed before the signature, so the call is
 *   `sigFuncName(sigConstantArgs..., sig)`
 * @property nClass the player's URL builder class used to apply the n-transform to the `n`
 *   throttling parameter
 */
data class PlayerCipherConfig(
    val sigFuncName: String,
    val sigConstantArgs: List<Int>,
    val nClass: String,
)

/**
 * Provides player.js cipher configs keyed by the 8-hex player hash (aliases included).
 *
 * The data is [ZemerTeam/zemer-cipher](https://github.com/ZemerTeam/zemer-cipher)'s
 * `player_configs.json` (GPL-3.0), whose upstream validates each entry against the live CDN
 * before publishing it. The bundled asset seeds this table for a fresh install, but YouTube
 * rotates players far more often than this app ships releases, so it is layered under a
 * runtime-refreshed copy fetched from upstream and cached to disk - the bundled copy alone would
 * otherwise silently go stale between releases and leave [CipherWebView] with no config to run,
 * the exact gap that let a player rotation break WEB deobfuscation outright until the next update.
 */
object PlayerCipherConfigStore {

    private const val TAG = "PlayerCipherConfig"
    private const val ASSET_NAME = "player_configs.json"
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    private const val CACHE_FILE_NAME = "player_configs_remote.json"

    // A stream failure on an unknown hash should get one refresh attempt, not one per song - a
    // whole album of the same rotten config would otherwise hit upstream once per track.
    private const val MIN_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()
    private val refreshMutex = Mutex()

    @Volatile
    private var lastRefreshAttemptMs = 0L

    // Bundled configs never change at runtime; the remote overlay is refreshed independently and
    // merged on top (remote wins on a hash collision, since it is the fresher of the two).
    private val bundled: Map<String, PlayerCipherConfig> by lazy { loadFromAsset() }

    @Volatile
    private var remote: Map<String, PlayerCipherConfig> = emptyMap()

    @Volatile
    private var merged: Map<String, PlayerCipherConfig> = emptyMap()

    init {
        remote = loadCachedRemote()
        rebuildMerged()
    }

    fun get(playerHash: String?): PlayerCipherConfig? = playerHash?.let { merged[it] }

    /** All known player hashes, aliases included. For diagnostics only. */
    fun knownHashes(): Set<String> = merged.keys

    /**
     * Called when [playerHash] was missing from the table. Fetches the latest upstream config
     * once per [MIN_REFRESH_INTERVAL_MS] (never more often, so a run of failing songs cannot spam
     * upstream), then reports whether [playerHash] is now known.
     */
    suspend fun ensureFreshFor(playerHash: String): Boolean {
        if (merged.containsKey(playerHash)) return true
        refresh(force = false)
        return merged.containsKey(playerHash)
    }

    /**
     * Fetches the latest `player_configs.json` from upstream and merges it over the bundled
     * table, persisting it to disk so the next app start has it without a network round trip.
     *
     * @param force bypass the [MIN_REFRESH_INTERVAL_MS] rate limit (used for an explicit
     *   user-triggered refresh; the automatic self-heal path always passes false)
     * @return true if a fetch was attempted and succeeded
     */
    suspend fun refresh(force: Boolean): Boolean = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAttemptMs < MIN_REFRESH_INTERVAL_MS) return@withLock false
        lastRefreshAttemptMs = now

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(REMOTE_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Remote player_configs.json fetch failed: HTTP ${response.code}")
                        return@withContext false
                    }
                    val text = response.body?.string() ?: return@withContext false
                    val parsed = parse(text)
                    if (parsed.isEmpty()) {
                        Log.w(TAG, "Remote player_configs.json parsed to zero entries, ignoring")
                        return@withContext false
                    }
                    remoteCacheFile().writeText(text)
                    remote = parsed
                    rebuildMerged()
                    Log.d(TAG, "Refreshed player cipher configs from upstream: ${parsed.size} entries")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh player_configs.json from upstream", e)
                false
            }
        }
    }

    private fun rebuildMerged() {
        merged = bundled + remote
    }

    private fun remoteCacheFile(): File = File(App.instance.filesDir, CACHE_FILE_NAME)

    private fun loadCachedRemote(): Map<String, PlayerCipherConfig> {
        val file = remoteCacheFile()
        if (!file.exists()) return emptyMap()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached remote player_configs.json", e)
            emptyMap()
        }
    }

    private fun loadFromAsset(): Map<String, PlayerCipherConfig> = try {
        val text = App.instance.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val result = parse(text)
        Log.d(TAG, "Loaded ${result.size} bundled player cipher configs")
        result
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $ASSET_NAME", e)
        emptyMap()
    }

    private fun parse(json: String): Map<String, PlayerCipherConfig> {
        val players = JSONObject(json).getJSONObject("players")
        val result = mutableMapOf<String, PlayerCipherConfig>()
        players.keys().forEach { hash ->
            val entry = players.getJSONObject(hash)
            val config = parseEntry(entry) ?: return@forEach
            result[hash] = config
            entry.optJSONArray("aliases")?.let { aliases ->
                for (i in 0 until aliases.length()) {
                    result[aliases.getString(i)] = config
                }
            }
        }
        return result
    }

    // sig is a `name(int,int,INPUT)` call; returns null on any malformed field so one bad entry can't break the map.
    private fun parseEntry(entry: JSONObject): PlayerCipherConfig? {
        val sig = entry.optString("sig")
        val nClass = entry.optString("nClass")
        if (sig.isEmpty() || nClass.isEmpty()) return null

        val open = sig.indexOf('(')
        if (open <= 0 || !sig.endsWith(")")) return null
        val funcName = sig.substring(0, open)
        val args = sig.substring(open + 1, sig.length - 1).split(",").map { it.trim() }
        if (args.lastOrNull() != "INPUT") return null
        val constants = args.dropLast(1).map { it.toIntOrNull() ?: return null }
        if (constants.isEmpty()) return null

        return PlayerCipherConfig(funcName, constants, nClass)
    }
}
