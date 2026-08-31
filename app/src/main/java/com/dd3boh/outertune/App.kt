/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.datastore.preferences.core.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountImageFetchedKey
import com.dd3boh.outertune.constants.AccountImageUrlKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.ArtworkFallbackToLowResKey
import com.dd3boh.outertune.constants.AutoBackupDefaults
import com.dd3boh.outertune.constants.AutoBackupEnabledKey
import com.dd3boh.outertune.constants.AutoBackupIntervalUnitKey
import com.dd3boh.outertune.constants.AutoBackupIntervalValueKey
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CountryCodeToName
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.LanguageCodeToName
import com.dd3boh.outertune.constants.MaxImageCacheSizeKey
import com.dd3boh.outertune.constants.ProxyEnabledKey
import com.dd3boh.outertune.constants.ProxyTypeKey
import com.dd3boh.outertune.constants.ProxyUrlKey
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.extensions.toInetSocketAddress
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.LocalArtworkPathKeyer
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.cipher.CipherDeobfuscator
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.artworkFallbackToLowRes
import com.dd3boh.outertune.utils.normalizeDataSyncId
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scheduleAutoBackup
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.kugou.KuGou
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Proxy
import java.util.Locale

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {
    private val TAG = App::class.simpleName.toString()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }

        instance = this;

        // The imported stream engine logs entirely through Timber, and Timber with no tree planted
        // discards everything - which is why the first build carrying it produced no cipher or
        // PoToken output at all. Planted unconditionally rather than only in debug builds, matching
        // Metrolist: reading its release-build logs is what made the stream failures diagnosable
        // here in the first place, and the same is worth having when this one misbehaves.
        Timber.plant(Timber.DebugTree())

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "") // replace zh-Hant-* to zh-*
        YouTube.locale = YouTubeLocale(
            gl = dataStore[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                ?: locale.country.takeIf { it in CountryCodeToName }
                ?: "US",
            hl = dataStore[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                ?: locale.language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en"
        )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }

        if (dataStore[ProxyEnabledKey] == true) {
            val proxyUrl = dataStore[ProxyUrlKey]
            // Blank/missing is "the toggle is on but nothing was ever saved" - not an error worth a
            // toast on every single launch. A non-blank string that still fails to parse is a real
            // misconfiguration and should still surface.
            if (!proxyUrl.isNullOrBlank()) {
                try {
                    YouTube.proxy = Proxy(
                        dataStore[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                        proxyUrl.toInetSocketAddress()
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to parse proxy url.", LENGTH_SHORT).show()
                    reportException(e)
                }
            }
        }

        if (dataStore[UseLoginForBrowse] != false) {
            YouTube.useLoginForBrowse = true
        }

        GlobalScope.launch {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData
                        ?.takeIf { it != "null" } // Previously visitorData was sometimes saved as "null" due to a bug
                        ?: YouTube.visitorData().onFailure {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@App, "Failed to get visitorData.", LENGTH_SHORT).show()
                            }
                            reportException(it)
                        }.getOrNull()?.also { newVisitorData ->
                            dataStore.edit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        }
                }
        }
        GlobalScope.launch {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId = normalizeDataSyncId(dataSyncId)
                }
        }
        GlobalScope.launch {
            dataStore.data
                .map { it[ArtworkFallbackToLowResKey] ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    artworkFallbackToLowRes = enabled
                }
        }
        // Reconciles the auto-backup job with WorkManager whenever the setting changes - this
        // collector fires immediately with whatever is currently stored too, so a schedule from a
        // previous install/run is picked up (or re-created) on cold start as well. Deliberately
        // not driven from MusicService: see AutoBackupWorker's own doc for why a DB checkpoint and
        // file copy has no business sharing a coroutine scope with anything playback-related.
        GlobalScope.launch {
            dataStore.data
                .map {
                    Triple(
                        it[AutoBackupEnabledKey] == true,
                        it[AutoBackupIntervalValueKey] ?: AutoBackupDefaults.INTERVAL_VALUE,
                        it[AutoBackupIntervalUnitKey].toEnum(AutoBackupDefaults.INTERVAL_UNIT)
                    )
                }
                .distinctUntilChanged()
                .collect { (enabled, intervalValue, intervalUnit) ->
                    scheduleAutoBackup(this@App, enabled, intervalValue, intervalUnit)
                }
        }
        // The imported cipher stack owns its own table now: a bundled asset as the offline floor,
        // overlaid by the Faraday and Zemer registries, refreshed on its own schedule. initialize()
        // is synchronous and only reads what is already on disk; the network refresh and the
        // WebView warm-up happen off the main thread below.
        CipherDeobfuscator.initialize(this)
        GlobalScope.launch(Dispatchers.IO) {
            // Deobfuscation runs in a WebView, and a cold one costs seconds. Warming it here means
            // the first song of a session does not pay for it.
            runCatching { CipherDeobfuscator.prewarm() }
            runCatching { YTPlayerUtils.prewarmPoToken() }
        }
        GlobalScope.launch {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    try {
                        YouTube.cookie = cookie
                    } catch (e: Exception) {
                        // we now allow user input now, here be the demons. This serves as a last ditch effort to avoid a crash loop
                        Log.e(TAG, "Could not parse cookie. Clearing existing cookie. ${e.message}")
                        forgetAccount(this@App)
                    }
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheSize = dataStore[MaxImageCacheSizeKey]

        // will crash app if you set to 0 after cache starts being used
        if (cacheSize == 0) {
            return ImageLoader.Builder(this)
                .components {
                    add(CoilBitmapLoader.Factory(this@App))
                    add(LocalArtworkPathKeyer())
                    add(ArtworkFallbackInterceptor())
                }
                .crossfade(true)
                .allowHardware(false)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.3)
                        .build()
                }
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }

        return ImageLoader.Builder(this)
            .components {
                add(CoilBitmapLoader.Factory(this@App))
                add(LocalArtworkPathKeyer())
                add(ArtworkFallbackInterceptor())
            }
            .crossfade(true)
            .allowHardware(false)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.3)
                    .build()
            }
            .diskCache(
                // Local images should bypass with DataSource.DISK
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes((cacheSize ?: 512) * 1024 * 1024L)
                    .build()
            )
            .build()
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(context: Context) {
            runBlocking {
                context.dataStore.edit { settings ->
                    settings.remove(InnerTubeCookieKey)
                    settings.remove(VisitorDataKey)
                    settings.remove(DataSyncIdKey)
                    settings.remove(AccountNameKey)
                    settings.remove(AccountEmailKey)
                    settings.remove(AccountChannelHandleKey)
                    settings.remove(AccountImageUrlKey)
                    settings.remove(AccountImageFetchedKey)
                }
            }
        }
    }
}