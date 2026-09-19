/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.my.kizzy.rpc.KizzyRPC
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything that can be changed, in one scrolling column.
 *
 * One page rather than the phone's tree of sub-screens. That tree exists because a phone can show
 * about six rows at a time, so anything longer has to be split; a desktop window shows forty, and
 * splitting them would mean navigating to find a switch that could simply have been visible.
 *
 * Signing in lives here rather than in its own top-level place. It is setup, done once, and its old
 * spot in the main navigation gave it standing next to the library and the playlists that it does
 * not have - nothing about signing in is something anyone visits twice.
 */
@Composable
fun SettingsPane(
    settings: Settings,
    account: Account,
    listenTogether: ListenTogetherManager,
    libraryPath: String,
    onBack: () -> Unit,
    onClearLyricsCache: () -> Unit,
    downloads: Downloads,
    onDownloadsChanged: () -> Unit,
    library: LibraryStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Library") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Section("Account") {
            // The whole sign-in pane, unchanged, just relocated. Four routes and their explanations
            // are a lot of surface, and it reads better as one card here than as a destination.
            AccountPane(account = account)
        }

        Section("Discord") {
            var pasting by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf("") }
            var testing by remember { mutableStateOf(false) }
            var connectionStatus by remember { mutableStateOf<String?>(null) }
            val isLoggedIn = settings.discordToken.isNotEmpty()

            // Resolves who is signed in once a token appears, the same way the phone's settings
            // screen does - so this shows a name rather than just "a token is set".
            LaunchedEffect(settings.discordToken) {
                if (settings.discordToken.isEmpty()) {
                    settings.discordUsername = ""
                    settings.discordDisplayName = ""
                    return@LaunchedEffect
                }
                if (settings.discordUsername.isNotEmpty()) return@LaunchedEffect
                KizzyRPC.getUserInfo(settings.discordToken).onSuccess { info ->
                    settings.discordUsername = info.username
                    settings.discordDisplayName = info.name
                }
            }

            if (isLoggedIn) {
                SettingRow(
                    title = settings.discordDisplayName.ifEmpty { settings.discordUsername },
                    subtitle = settings.discordUsername.takeIf { it.isNotEmpty() }?.let { "@$it" },
                    icon = OuterTuneIcons.accountCircle,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SettingSwitch(
                    title = "Show what's playing on Discord",
                    subtitle = "A card on the profile, the same one the phone app shows.",
                    checked = settings.enableDiscordRpc,
                    onCheckedChange = { settings.enableDiscordRpc = it },
                    icon = OuterTuneIcons.discord,
                )
                SettingRow(
                    title = if (testing) "Testing…" else "Test connection",
                    subtitle = connectionStatus,
                    icon = OuterTuneIcons.networkCheck,
                    onClick = {
                        if (testing) return@SettingRow
                        testing = true
                        connectionStatus = "Testing…"
                        scope.launch {
                            val rpc = KizzyRPC(settings.discordToken)
                            connectionStatus = rpc.testConnection()
                            rpc.closeRPC()
                            testing = false
                        }
                    },
                )
                SettingRow(
                    title = "Log out",
                    icon = OuterTuneIcons.logout,
                    onClick = {
                        settings.discordToken = ""
                        settings.discordUsername = ""
                        settings.discordDisplayName = ""
                        connectionStatus = null
                    },
                )
            } else {
                Text(
                    text = "Signs a real Discord account in over the same connection the Discord " +
                        "client itself uses - there is no bot or OAuth path to a \"currently " +
                        "listening to\" card on your own profile. Discord's terms discourage " +
                        "driving an account this way; that risk is yours to weigh, on the account " +
                        "you paste a token from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SettingRow(
                    title = if (pasting) "Hide" else "Paste a Discord token",
                    icon = OuterTuneIcons.key,
                    onClick = { pasting = !pasting },
                )
                if (pasting) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Discord token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            settings.discordToken = draft.trim().trim('"', '\'')
                            draft = ""
                            pasting = false
                        },
                    ) { Text("Save") }
                }
            }
        }

        Section("Listen Together") {
            val mode by listenTogether.mode.collectAsState()
            val listeners by listenTogether.listeners.collectAsState()
            val follower by listenTogether.followerState.collectAsState()
            val reconnecting by listenTogether.reconnecting.collectAsState()
            val ltError by listenTogether.error.collectAsState()

            // Only while there is something to browse for. The nearby list is rendered in the OFF
            // branch alone, so browsing in the other two kept a jmDNS scan running for a list
            // nobody could see - the exact cost the flow is built cold to avoid. It also sidesteps
            // a latent oddity: the name a host excludes to avoid listing itself is read when the
            // flow is built, and while hosting that is still null, because advertising happens
            // later on the bind callback.
            val hosts by if (mode == ListenTogetherMode.OFF) {
                remember(mode) { listenTogether.discoverHosts() }.collectAsState(initial = emptyList())
            } else {
                remember { mutableStateOf(emptyList<DiscoveredHost>()) }
            }
            val deviceName = remember { listenTogether.deviceName() }

            ltError?.let {
                SettingRow(
                    title = it,
                    icon = OuterTuneIcons.info,
                    onClick = { listenTogether.clearError() },
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            when (mode) {
                ListenTogetherMode.HOSTING -> {
                    SettingRow(
                        title = deviceName,
                        subtitle = "Sharing what's playing on this network",
                        icon = OuterTuneIcons.castConnected,
                    )
                    SettingRow(
                        title = "Stop sharing",
                        icon = OuterTuneIcons.stop,
                        onClick = { listenTogether.stop() },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Listeners (${listeners.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (listeners.isEmpty()) {
                        // Not an error, and worth saying so. The most common reason nobody has
                        // joined is simply that nobody has opened this screen on the other device.
                        Text(
                            "Waiting for someone to join…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        listeners.forEach { listener ->
                            SettingRow(
                                title = listener.name,
                                subtitle = listener.address,
                                icon = OuterTuneIcons.person,
                            )
                        }
                    }
                }

                ListenTogetherMode.FOLLOWING -> {
                    SettingRow(
                        title = follower.hostName ?: "Connecting…",
                        // While reconnecting the drift figure describes a link that is gone, so
                        // saying what is happening beats reporting a stale measurement as though
                        // the session were still keeping time.
                        subtitle = if (reconnecting) {
                            "Reconnecting…"
                        } else {
                            followerStatus(follower.synced, follower.driftMs)
                        },
                        icon = OuterTuneIcons.cast,
                    )
                    follower.track?.let { track ->
                        SettingRow(
                            title = track.title,
                            subtitle = track.artist,
                            icon = OuterTuneIcons.musicNote,
                        )
                    }
                    if (follower.unavailable) {
                        // A file on the host's own storage. There is nothing to fetch, so saying so
                        // is the whole of the correct behaviour - retrying would never succeed.
                        Text(
                            "The host is playing a local file, which cannot be fetched from here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    if (follower.missingTrack) {
                        Text(
                            "That song could not be found here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    SettingRow(
                        title = "Leave session",
                        icon = OuterTuneIcons.stop,
                        onClick = { listenTogether.stop() },
                    )
                }

                ListenTogetherMode.OFF -> {
                    SettingRow(
                        title = "Start sharing",
                        subtitle = if (listenTogether.canStart) {
                            "Others on this network can hear what plays here, kept in sync."
                        } else {
                            "Start playing something first."
                        },
                        icon = OuterTuneIcons.cast,
                        onClick = { if (listenTogether.canStart) listenTogether.startHosting() },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Nearby",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (hosts.isEmpty()) {
                        // The list only ever contains devices already sharing, so an empty list is
                        // ambiguous between "still looking" and "nobody is sharing". Saying both,
                        // with the one thing that can actually be checked, beats an endless spinner.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                "Looking for devices sharing on this network…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        hosts.forEach { host ->
                            SettingRow(
                                title = host.name,
                                subtitle = host.address.hostAddress,
                                icon = OuterTuneIcons.devices,
                                onClick = { listenTogether.join(host) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Icon(
                    imageVector = OuterTuneIcons.timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Timing offset", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "How far ahead of the host to aim. Set by ear - it corrects for this " +
                            "device's own output latency, which nothing here can measure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RotaryDial(
                    value = settings.listenTogetherOffsetMs.toFloat(),
                    onValueChange = { settings.listenTogetherOffsetMs = it.toInt() },
                    valueRange = -500f..500f,
                    color = if (settings.colourByValue) {
                        ValueGradient.forValue(settings.listenTogetherOffsetMs.toFloat(), -500f..500f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textColor = MaterialTheme.colorScheme.onSurface,
                    dialSize = 68.dp,
                    valueLabel = "${settings.listenTogetherOffsetMs} ms",
                    step = 10f,
                    coarseStep = 100f,
                    centeredAt = 0f,
                )
            }
        }

        Section("Playback") {
            SettingSwitch(
                title = "Seek buttons",
                subtitle = "Five-second skip either side of play. Off by default, as on the phone.",
                checked = settings.showSeekButtons,
                onCheckedChange = { settings.showSeekButtons = it },
                icon = OuterTuneIcons.fastForward,
            )
            SettingSwitch(
                title = "Visualiser",
                subtitle = "Spectrum bars above the seek bar.",
                checked = settings.showVisualizer,
                onCheckedChange = { settings.showVisualizer = it },
                icon = OuterTuneIcons.graphicEq,
            )
        }

        Section("Lyrics") {
            SettingSwitch(
                title = "Word-by-word lyrics",
                subtitle = "Asks BetterLyrics first, which times each word rather than each line. " +
                    "Falls back to LRCLIB and KuGou either way.",
                checked = settings.wordByWordLyrics,
                onCheckedChange = { settings.wordByWordLyrics = it },
                icon = OuterTuneIcons.textRotationAngledown,
            )
            SettingSwitch(
                title = "Click the cover for lyrics",
                subtitle = "Swaps the album art for the words, and back.",
                checked = settings.lyricsOnCoverClick,
                onCheckedChange = { settings.lyricsOnCoverClick = it },
                icon = OuterTuneIcons.lyrics,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Icon(
                    imageVector = OuterTuneIcons.timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Timing offset", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Shifts every line. Positive is later. Some files are timed against a " +
                            "different master of the same song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RotaryDial(
                    value = settings.lyricsOffsetMs.toFloat(),
                    onValueChange = { settings.lyricsOffsetMs = it.toInt() },
                    valueRange = -3000f..3000f,
                    color = if (settings.colourByValue) {
                        ValueGradient.forValue(settings.lyricsOffsetMs.toFloat(), -3000f..3000f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textColor = MaterialTheme.colorScheme.onSurface,
                    dialSize = 68.dp,
                    valueLabel = "${settings.lyricsOffsetMs} ms",
                    step = 25f,
                    coarseStep = 250f,
                    centeredAt = 0f,
                )
            }
            SettingRow(title = "Clear cached lyrics", icon = OuterTuneIcons.delete, onClick = onClearLyricsCache)
        }

        Section("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = OuterTuneIcons.darkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
            }
            ThemeMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settings.themeMode = mode }
                        .padding(vertical = 2.dp),
                ) {
                    RadioButton(selected = settings.themeMode == mode, onClick = { settings.themeMode = mode })
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                title = "Colour the app from the artwork",
                subtitle = "The whole window takes its palette from the cover of what is playing, " +
                    "the way the phone takes its own from the wallpaper.",
                checked = settings.dynamicTheme,
                onCheckedChange = { settings.dynamicTheme = it },
                icon = OuterTuneIcons.palette,
            )
            SettingSwitch(
                title = "Colour controls by value",
                subtitle = "Yellow low, green middle, blue high, on dials and equaliser bands.",
                checked = settings.colourByValue,
                onCheckedChange = { settings.colourByValue = it },
                icon = OuterTuneIcons.colorLens,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = OuterTuneIcons.blurOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Text("Player background", style = MaterialTheme.typography.bodyLarge)
            }
            BackgroundStyle.entries.forEach { style ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settings.background = style }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = settings.background == style,
                        onClick = { settings.background = style },
                    )
                    Column {
                        Text(style.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            style.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Section("Downloads") {
            // Recomputed when the screen is opened rather than watched. Nothing else changes it
            // while this is on screen, and a live total would mean stat-ing every file on a timer.
            var total by remember { mutableStateOf(0L) }
            var count by remember { mutableStateOf(0) }
            // Same reasoning as the player's: totalBytes() stats every downloaded file, which is
            // not something to do while laying out a screen.
            LaunchedEffect(downloads) {
                withContext(Dispatchers.IO) {
                    val ids = downloads.ids()
                    val bytes = downloads.totalBytes()
                    withContext(Dispatchers.Main) {
                        count = ids.size
                        total = bytes
                    }
                }
            }
            Text(
                text = if (count == 0) {
                    "Nothing downloaded yet. The download button on the player keeps a song on disk."
                } else {
                    "$count ${if (count == 1) "song" else "songs"}, ${Downloads.formatSize(total)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Downloaded songs play without the network, and without asking YouTube " +
                    "whether they are still available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count > 0) {
                SettingRow(
                    title = "Delete all downloads",
                    icon = OuterTuneIcons.delete,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        total = 0
                        count = 0
                        scope.launch {
                            withContext(Dispatchers.IO) { downloads.deleteAll() }
                            onDownloadsChanged()
                        }
                    },
                )
            }
        }

        Section("Library") {
            SettingSwitch(
                title = "Keep listening history",
                subtitle = "Records what has been played, which is what the recent list is made of.",
                checked = settings.keepHistory,
                onCheckedChange = { settings.keepHistory = it },
                icon = OuterTuneIcons.history,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Merged into this library rather than replacing it, and safe to run twice - see
            // BackupImport. The summary is spelled out rather than reduced to "done", because the
            // interesting part of an import is what it did *not* bring across.
            var importing by remember { mutableStateOf(false) }
            var importResult by remember { mutableStateOf<ImportSummary?>(null) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = OuterTuneIcons.restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Text("Import from a phone backup", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = "Reads the zip the Android app writes. Songs, liked songs and playlists are " +
                    "added to what is already here; nothing is replaced, and importing the same " +
                    "file twice changes nothing the second time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !importing,
                    onClick = {
                        val file = chooseBackupFile() ?: return@TextButton
                        importing = true
                        importResult = null
                        scope.launch {
                            importResult = BackupImport.importFrom(file, library)
                            importing = false
                        }
                    },
                ) {
                    Text(if (importing) "Importing…" else "Choose backup…")
                }
                importResult?.let { summary ->
                    Text(
                        text = summary.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Stored at", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = libraryPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "One SQLite file. Copying it somewhere safe is a complete backup - songs, " +
                    "playlists, history, cached lyrics and these settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/**
 * The OS file picker, asking for a backup.
 *
 * Same `FileDialog` the sign-in flow uses, for the same reason: it is the native dialog, so it looks
 * like every other open dialog on the machine and starts where the person last saved something.
 */
private fun chooseBackupFile(): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a backup", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return java.io.File(directory, name)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScopeMarker.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    Card(modifier = Modifier.widthIn(max = 720.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ColumnScopeMarker.content()
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * A marker receiver so section bodies read as a block rather than as a lambda argument.
 *
 * There is nothing on it. Compose's own ColumnScope cannot be passed through here without the
 * content having to declare it, and the sections do not need column-scoped modifiers anyway.
 */
object ColumnScopeMarker

/**
 * How well a Listen Together follower is keeping up, in words rather than a number.
 *
 * A raw millisecond figure invites worrying about a value nothing here can be done about directly;
 * the only distinction that matters to a listener is whether the two devices sound like one.
 */
private fun followerStatus(synced: Boolean, driftMs: Long): String = when {
    !synced -> "Measuring the connection…"
    kotlin.math.abs(driftMs) < 40 -> "In sync"
    kotlin.math.abs(driftMs) < 250 -> "Adjusting…"
    else -> "Catching up…"
}

/**
 * @param icon Leading glyph, matching the icon Android's `PreferenceEntry`/`SwitchPreference` shows
 *   in the same spot on the phone - see [OuterTuneIcons] and its per-icon "Android:" doc references.
 *   Null for the handful of switches that are desktop-only and have no phone equivalent to match.
 */
@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch. A switch is a small target and the label
            // beside it is the thing being read - clicking what you just read should work.
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/**
 * A clickable icon/title/subtitle row - the desktop's equivalent of Android's `PreferenceEntry`,
 * built for the same reason [SettingSwitch] carries an icon: so a row looks like the one it mirrors
 * on the phone rather than like a plain button with a caption.
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tint == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
