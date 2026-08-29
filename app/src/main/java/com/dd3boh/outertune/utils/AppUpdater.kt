/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.dd3boh.outertune.BuildConfig
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Checks GitHub Releases for a newer build and hands it to the system installer.
 *
 * The app never installs anything itself and could not if it wanted to. All this does is download
 * an apk and ask Android to open it; Android requires the user to have allowed "install unknown
 * apps" for this app, and then shows its own confirmation for every single install. So the user's
 * side of an update is one tap, but it is always their tap.
 *
 * Two things this depends on that are easy to get wrong, both worth stating here because the
 * failure modes are opaque:
 *
 * - The release must carry the apk as a **release asset**. Workflow artifacts are not usable: they
 *   are zipped and need an authenticated API call, so nothing can fetch one from a plain install.
 * - Every build must be signed with the **same key**. Android refuses to update a package whose
 *   signature changed, with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way through is an
 *   uninstall - which takes the library with it. A workflow that generates a throwaway keystore
 *   per run can therefore never produce an installable update, however correct this code is.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    /** The fork this build is distributed from - matches the repository the workflow runs in. */
    private const val RELEASES_API = "https://api.github.com/repos/notk50bml/outertune/releases/latest"

    /**
     * The build filename applicationVariants.all assembles, e.g.
     * `OuterTune-0.19-arm64-v8a-core-release-147.apk`. The trailing number is the versionCode,
     * which is what an upgrade is actually decided on - versionName is free text and two different
     * builds routinely share one.
     */
    private val ASSET_NAME = Regex("""^OuterTune-.*-(\d+)\.apk$""")

    private val httpClient by lazy {
        OkHttpClient.Builder().proxy(YouTube.proxy).build()
    }

    data class Update(
        val versionCode: Int,
        val versionName: String,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val releaseNotes: String,
    )

    /**
     * The newest release asset that is both newer than this build and actually installable on this
     * device, or null. Never throws: an update check failing is not worth interrupting anything
     * over, so it is logged and treated as "nothing to update to".
     */
    /**
     * @param flavor which build flavour to look for. Defaults to the one running, which is almost
     *   always what is wanted; it is settable so someone on core can move to full (or back) by
     *   taking the next update, rather than having to find and sideload an apk by hand.
     */
    suspend fun checkForUpdate(flavor: String = BuildConfig.FLAVOR): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()

            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "release check failed: HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string() ?: return@withContext null
            }

            val release = JSONObject(body)
            val versionName = release.optString("tag_name").removePrefix("v")
            val notes = release.optString("body").orEmpty()
            val assets = release.optJSONArray("assets") ?: return@withContext null

            // Only an apk built for this device's abi and this build's flavour can replace this
            // install. A universal apk is accepted too, since it contains every abi.
            val abis = Build.SUPPORTED_ABIS.toSet()

            var best: Update? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val code = ASSET_NAME.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue

                val abiMatches = name.contains("universal") || abis.any { name.contains(it) }
                if (!abiMatches || !name.contains(flavor, ignoreCase = true)) continue
                if (code <= BuildConfig.VERSION_CODE) continue
                if (best != null && code <= best.versionCode) continue

                best = Update(
                    versionCode = code,
                    versionName = versionName.ifBlank { code.toString() },
                    assetName = name,
                    downloadUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                    releaseNotes = notes,
                )
            }

            best?.also { Log.i(TAG, "update available: ${it.assetName} (${it.versionCode} > ${BuildConfig.VERSION_CODE})") }
        }.onFailure { Log.w(TAG, "release check failed", it) }.getOrNull()
    }

    /**
     * Downloads [update] and opens the system installer on it.
     *
     * [onProgress] is called with 0f..1f as bytes arrive, so a caller can show something during
     * what is a several-megabyte download. Returns the failure rather than throwing so the caller
     * can put the reason in front of the user.
     */
    suspend fun downloadAndInstall(
        context: Context,
        update: Update,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Kept in cacheDir, which provider_paths already exposes, so a half-finished or
            // abandoned download is something the system can reclaim on its own rather than a file
            // that sits in app storage forever.
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, update.assetName)

            val request = Request.Builder().url(update.downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    error("Download failed: HTTP ${response.code}")
                }
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            // A truncated download is worse than a failed one: the installer rejects it with a
            // parse error that reads like a corrupt build rather than a lost connection.
            if (update.sizeBytes > 0 && apk.length() != update.sizeBytes) {
                apk.delete()
                error("Download incomplete (${apk.length()} of ${update.sizeBytes} bytes)")
            }

            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.FileProvider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                // The installer runs in another process, so it needs both the read grant and its
                // own task - it is not a screen of this app.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "handed ${apk.name} to the system installer")
            Unit
        }.onFailure { Log.e(TAG, "update download/install failed", it) }
    }
}
