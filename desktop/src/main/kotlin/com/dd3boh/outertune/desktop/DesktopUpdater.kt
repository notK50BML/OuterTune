/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates from the project's GitHub releases - the phone's AppUpdater, for the Windows installer.
 *
 * What it will fetch is deliberately narrow, because what it fetches it then runs:
 * - only a release asset named `OuterTune-setup-<x.y.z>.exe` ([ASSET]), from a published release
 *   (no drafts, no prereleases), newer than this build;
 * - only from GitHub's own hosts, the release page and its download servers, redirects included;
 * - only if the bytes are the size GitHub lists, match the SHA-256 GitHub publishes for the asset
 *   when it does, and are a Windows executable (they begin with "MZ").
 *
 * It is kept in a folder of its own under the system's temp directory, and that folder is emptied at
 * every start - the installer cannot delete itself while it runs, so the copy that just installed is
 * removed the next time OuterTune opens.
 */
object DesktopUpdater {

    /** One newer release, as found. */
    data class Update(
        /** As the release names itself - "0.4b" - for showing. */
        val name: String,
        val version: List<Int>,
        val assetName: String,
        val url: String,
        val size: Long,
        /** Lower-case hex, when GitHub publishes one for the asset. */
        val sha256: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: Update) : State
        data class Downloading(val update: Update, val progress: Float) : State
        data class Ready(val update: Update, val installer: File) : State
        data class Failed(val reason: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private const val RELEASES_URL = "https://api.github.com/repos/notk50bml/outertune/releases?per_page=30"

    /** The installer, and nothing else: `OuterTune-setup-0.4.0.exe`. */
    private val ASSET = Regex("""^OuterTune-setup-(\d+)\.(\d+)\.(\d+)\.exe$""")

    /** Where a release asset can come from: GitHub, and the servers it redirects downloads to. */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    private val folder: File get() = File(System.getProperty("java.io.tmpdir"), "OuterTune-update")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Removes what an earlier update downloaded. Called at start; harmless when there is nothing. */
    fun cleanUp() {
        runCatching { folder.listFiles()?.forEach { it.delete() } }
    }

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        state.value = State.Checking
        runCatching {
            val body = http.newCall(
                Request.Builder().url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "OuterTune-desktop/${DesktopVersion.NAME}")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                response.body.string()
            }
            pickUpdate(body, parseVersion(DesktopVersion.NUMERIC) ?: listOf(0, 0, 0))
        }.fold(
            onSuccess = { update ->
                state.value = if (update != null) State.Available(update) else State.UpToDate
                update
            },
            onFailure = {
                state.value = State.Failed("Could not check for updates: ${it.message}")
                null
            },
        )
    }

    /** Downloads [update]'s installer into the temp folder and checks it - see the class doc. */
    suspend fun download(update: Update): File? = withContext(Dispatchers.IO) {
        state.value = State.Downloading(update, 0f)
        runCatching {
            require(ASSET.matches(update.assetName)) { "not an installer: ${update.assetName}" }
            require(hostOf(update.url) in ALLOWED_HOSTS) { "not a GitHub download: ${update.url}" }
            folder.mkdirs()
            cleanUp()
            val target = File(folder, update.assetName)
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(
                Request.Builder().url(update.url).header("User-Agent", "OuterTune-desktop/${DesktopVersion.NAME}").build()
            ).execute().use { response ->
                // Redirects are followed - GitHub sends downloads to its asset servers - but where it
                // ended up must still be one of them.
                require(hostOf(response.request.url.toString()) in ALLOWED_HOSTS) {
                    "redirected away from GitHub, to ${response.request.url.host}"
                }
                if (!response.isSuccessful) error("download answered ${response.code}")
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            if (copied > update.size) error("larger than GitHub says it is")
                            state.value = State.Downloading(update, (copied.toFloat() / update.size).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (target.length() != update.size) error("incomplete: ${target.length()} of ${update.size} bytes")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (update.sha256 != null && update.sha256 != hex) error("its checksum does not match GitHub's")
            if (!isWindowsExecutable(target)) error("not a Windows program")
            target
        }.fold(
            onSuccess = { file ->
                state.value = State.Ready(update, file)
                file
            },
            onFailure = {
                cleanUp()
                state.value = State.Failed("Could not download the update: ${it.message}")
                null
            },
        )
    }

    /**
     * Runs the downloaded installer as an update - silently, closing this app, which the installer
     * starts again when it is done - and returns true if it started. The caller then exits.
     */
    fun install(installer: File): Boolean {
        if (!installer.isFile || !ASSET.matches(installer.name) || !isWindowsExecutable(installer)) return false
        return runCatching {
            ProcessBuilder(installer.absolutePath, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS")
                .directory(installer.parentFile)
                .start()
        }.isSuccess
    }

    /**
     * The newest release with an installer newer than [current], from GitHub's releases list -
     * pure, for testing. Drafts and prereleases are passed over, as on the phone.
     */
    internal fun pickUpdate(releasesJson: String, current: List<Int>): Update? {
        val releases = Json.parseToJsonElement(releasesJson) as? JsonArray ?: return null
        var best: Update? = null
        for (element in releases) {
            val release = element as? JsonObject ?: continue
            if ((release["draft"] as? JsonPrimitive)?.booleanOrNull == true) continue
            if ((release["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val name = ((release["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (release["tag_name"] as? JsonPrimitive)?.content.orEmpty()).removePrefix("v")
            val assets = release["assets"] as? JsonArray ?: continue
            for (asset in assets) {
                val a = asset as? JsonObject ?: continue
                val assetName = (a["name"] as? JsonPrimitive)?.content ?: continue
                val match = ASSET.matchEntire(assetName) ?: continue
                val version = match.groupValues.drop(1).map { it.toInt() }
                val url = (a["browser_download_url"] as? JsonPrimitive)?.content ?: continue
                if (hostOf(url) != "github.com") continue
                val size = (a["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: continue
                val sha256 = (a["digest"] as? JsonPrimitive)?.content
                    ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                if (compareVersions(version, current) <= 0) continue
                if (best == null || compareVersions(version, best.version) > 0) {
                    best = Update(name.ifBlank { version.joinToString(".") }, version, assetName, url, size, sha256)
                }
            }
        }
        return best
    }

    internal fun parseVersion(text: String): List<Int>? =
        text.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    /** Whether [file] starts the way every Windows program does: "MZ". */
    internal fun isWindowsExecutable(file: File): Boolean = runCatching {
        file.inputStream().use { val head = ByteArray(2); it.read(head) == 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte() }
    }.getOrDefault(false)
}
