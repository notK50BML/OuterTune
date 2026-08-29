/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2026 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.utils.AppUpdater
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.LYRIC_FETCH_TIMEOUT
import com.dd3boh.outertune.constants.MAX_LM_SCANNER_JOBS
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.SNACKBAR_VERY_SHORT
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ContributorCard
import com.dd3boh.outertune.ui.component.ContributorInfo
import com.dd3boh.outertune.ui.component.ContributorType.CUSTOM
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.IconLabelButton
import com.dd3boh.outertune.ui.utils.backToMain
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val resources = LocalResources.current
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    val showDebugInfo = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "userdebug"

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Deliberately not remembered across navigation: an update check is cheap, and a stale
    // "up to date" from ten minutes ago is worse than no answer at all.
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateInProgress by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground, BlendMode.SrcIn),
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                .clickable { }
        )

        Row(
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "OuterTune",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.width(4.dp))

            if (showDebugInfo) {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = BuildConfig.BUILD_TYPE.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            IconLabelButton(
                text = "GitHub",
                painter = painterResource(R.drawable.github),
                onClick = { uriHandler.openUri("https://github.com/notK50BML/OuterTune/") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            IconLabelButton(
                text = stringResource(R.string.wiki),
                icon = Icons.Outlined.Info,
                onClick = { uriHandler.openUri("https://github.com/notK50BML/OuterTune/wiki") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(96.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.check_for_updates)) },
                    description = updateStatus,
                    onClick = {
                        if (updateInProgress) return@PreferenceEntry
                        coroutineScope.launch {
                            updateInProgress = true
                            val available = AppUpdater.checkForUpdate()
                            if (available == null) {
                                updateStatus = context.getString(R.string.update_up_to_date)
                                updateInProgress = false
                                return@launch
                            }
                            updateStatus = context.getString(R.string.update_downloading, available.versionName, 0)
                            AppUpdater.downloadAndInstall(context, available) { fraction ->
                                updateStatus = context.getString(
                                    R.string.update_downloading,
                                    available.versionName,
                                    (fraction * 100).toInt(),
                                )
                            }.onSuccess {
                                // The system installer takes it from here, and whether the user
                                // goes through with it is not something this screen gets told.
                                updateStatus = context.getString(R.string.update_handed_to_installer)
                            }.onFailure {
                                updateStatus = context.getString(R.string.update_failed, it.message.orEmpty())
                            }
                            updateInProgress = false
                        }
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.attribution_title)) },
                    onClick = {
                        navController.navigate("settings/about/attribution")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.oss_licenses_title)) },
                    onClick = {
                        navController.navigate("settings/about/oss_licenses")
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_bug_report_action)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/notK50BML/OuterTune/issues")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_support_forum)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/notK50BML/OuterTune/discussions")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_contact_email_inquiries)) },
                    onClick = {
                        val clipData = ClipData.newPlainText(
                            resources.getString(R.string.app_name),
                            AnnotatedString("k50bml@gmail.com")
                        )
                        clipboardManager.nativeClipboard.setPrimaryClip(clipData)
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsClickToReveal(stringResource(R.string.app_info_title)) {
                    val info = mutableListOf<String>(
                        "TagLib: ${BuildConfig.TAGLIB_VERSION}",
                        "FFmpeg decoder: $ENABLE_FFMETADATAEX",
                        "LM scanner concurrency: $MAX_LM_SCANNER_JOBS",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "OOBE_VERSION: $OOBE_VERSION",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "SNACKBAR_VERY_SHORT: $SNACKBAR_VERY_SHORT"
                    )
                    if (ENABLE_FFMETADATAEX) {
                        info.add("FFmpeg version: ${FfmpegLibrary.getVersion()}")
                        info.add("FFmpeg isAvailable: ${FfmpegLibrary.isAvailable()}")
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        info.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                SettingsClickToReveal(stringResource(R.string.device_info_title)) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val info = mutableListOf<String>(
                            "Device: ${Build.BRAND} ${Build.DEVICE} (${Build.MODEL})",
                            "Manufacturer: ${Build.MANUFACTURER}",
                            "HW: ${Build.BOARD} (${Build.HARDWARE})",
                            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()})",
                            "Android: ${Build.VERSION.SDK_INT} (${Build.ID})",
                            Build.DISPLAY,
                            Build.PRODUCT,
                            Build.FINGERPRINT,
                            Build.VERSION.SECURITY_PATCH
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.add("SOC: ${Build.SOC_MODEL} (${Build.SOC_MANUFACTURER})")
                            info.add("SKU: ${Build.SKU} (${Build.ODM_SKU})")
                        }

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            info.forEach {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (ENABLE_FFMETADATAEX) {
                ContributorCard(
                    contributor = ContributorInfo(
                        name = "FFmpeg",
                        description = stringResource(R.string.ffmpeg_lgpl),
                        type = listOf(CUSTOM),
                        url = "https://github.com/OuterTune/ffMetadataEx/blob/main/Modules.md"
                    )
                )
            }
        }

    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
    ) {
        AboutScreen(
            navController = rememberNavController(),
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        )
    }
}
