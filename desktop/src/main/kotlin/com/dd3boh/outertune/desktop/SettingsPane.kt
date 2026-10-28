/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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

/** The sections settings are grouped into, in the phone's order. */
enum class SettingsCategory(val label: String, val blurb: String) {
    // The phone's sections, in its order and under its names (SettingsScreen.kt), so a setting is
    // found in the same place on both. Keyboard is the one the phone has no need for.
    Account("Account and sync", "YouTube Music, sync and Discord"),
    Appearance("Appearance", "Theme, colours, fonts, tabs and the player's look"),
    Player("Player and audio", "Buttons, seek bar, queue, audio and the sleep timer"),
    ListenTogether("Listen Together", "Play in sync with other devices"),
    Content("Library and content", "Language, region and what shows up"),
    Local("Local media", "Folders on this computer"),
    Lyrics("Lyrics", "Providers, timing and how lyrics look"),
    Keyboard("Keyboard", "Shortcuts for the player, each remappable"),
    Privacy("Privacy", "Listening and search history"),
    Storage("Storage", "Downloaded songs"),
    Backup("Backup and restore", "Backups, the phone's backups, settings profiles"),
    About("About", "Version and links"),
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.Appearance -> OuterTuneIcons.palette
    SettingsCategory.Player -> OuterTuneIcons.play
    SettingsCategory.Lyrics -> OuterTuneIcons.lyrics
    SettingsCategory.Keyboard -> OuterTuneIcons.keyboard
    SettingsCategory.Content -> OuterTuneIcons.musicNote
    SettingsCategory.Account -> OuterTuneIcons.accountCircle
    SettingsCategory.ListenTogether -> OuterTuneIcons.cast
    SettingsCategory.Local -> OuterTuneIcons.musicNote
    SettingsCategory.Storage -> OuterTuneIcons.download
    SettingsCategory.Privacy -> OuterTuneIcons.history
    SettingsCategory.Backup -> OuterTuneIcons.restore
    SettingsCategory.About -> OuterTuneIcons.info
}

/**
 * Every setting by name, for search.
 *
 * Hand-kept, next to the screens that draw them. Keywords are the words someone might type that are
 * not in the title - "dark" for the theme, "karaoke" for word-by-word - which is most of what makes
 * a settings search useful rather than a find-in-page.
 */
internal data class SettingEntry(val title: String, val category: SettingsCategory, val keywords: String = "")

internal val SETTING_ENTRIES = listOf(
    SettingEntry("Use login for browsing content", SettingsCategory.Account, "personal recommendations premium guest signed in"),
    SettingEntry("Show login token", SettingsCategory.Account, "cookie token copy session advanced login"),
    SettingEntry("Sync automatically", SettingsCategory.Account, "auto sync startup youtube music account"),
    SettingEntry("Sync now", SettingsCategory.Account, "sync manual refresh library liked"),
    SettingEntry("What to sync", SettingsCategory.Account, "liked songs library songs albums artists playlists recent activity"),
    SettingEntry("Sync mode", SettingsCategory.Account, "read only read write push likes"),
    SettingEntry("Conflict resolution", SettingsCategory.Account, "overwrite keep local add remote"),
    SettingEntry("Scroll in the player", SettingsCategory.Player, "scroll wheel anywhere close dismiss put away open equaliser"),
    SettingEntry("Song rows", SettingsCategory.Appearance, "row buttons three dots menu like download quick actions"),
    SettingEntry("Queues to keep", SettingsCategory.Player, "multiple queues saved switch board history"),
    SettingEntry("Player layout", SettingsCategory.Appearance, "layout editor custom arrange move cover position import"),
    SettingEntry("Tabs", SettingsCategory.Appearance, "rail navigation sections home songs artists albums playlists library local stats order"),
    SettingEntry("Keyboard shortcuts", SettingsCategory.Keyboard, "keys keybinds hotkeys shortcut remap play pause next previous equaliser shuffle repeat volume"),
    SettingEntry("Theme", SettingsCategory.Appearance, "dark light system mode night"),
    SettingEntry("Pure black", SettingsCategory.Appearance, "amoled oled dark black"),
    SettingEntry("Colour the app from the artwork", SettingsCategory.Appearance, "dynamic material you palette cover color"),
    SettingEntry("Colour controls by value", SettingsCategory.Appearance, "gradient yellow green blue dials color"),
    SettingEntry("Player background", SettingsCategory.Appearance, "gradient blur frosted solid"),
    SettingEntry("Font", SettingsCategory.Appearance, "typeface family text system fonts"),
    SettingEntry("Text size", SettingsCategory.Appearance, "scale zoom font size bigger smaller"),
    SettingEntry("Open on", SettingsCategory.Appearance, "start startup default tab section home"),
    SettingEntry("Player buttons", SettingsCategory.Player, "like download sleep menu order arrange actions"),
    SettingEntry("Seek buttons", SettingsCategory.Player, "skip forward rewind five seconds"),
    SettingEntry("Visualiser", SettingsCategory.Player, "spectrum bars visualizer"),
    SettingEntry("Show queue title", SettingsCategory.Player, "queue name handle"),
    SettingEntry("Queue handle in the title bar", SettingsCategory.Player, "queue handle top bottom banner window buttons"),
    SettingEntry("Equaliser", SettingsCategory.Player, "eq equalizer bass treble compressor autoeq"),
    SettingEntry("Provider selection", SettingsCategory.Lyrics, "provider automatic order priority lrclib kugou betterlyrics source"),
    SettingEntry("BetterLyrics", SettingsCategory.Lyrics, "word by word karaoke syllable provider"),
    SettingEntry("Estimate word timings", SettingsCategory.Lyrics, "karaoke interpolation sweep"),
    SettingEntry("Click the cover for lyrics", SettingsCategory.Lyrics, "artwork tap"),
    SettingEntry("Lyrics text size", SettingsCategory.Lyrics, "font scale"),
    SettingEntry("Lyrics alignment", SettingsCategory.Lyrics, "centre center left"),
    SettingEntry("Lyrics timing offset", SettingsCategory.Lyrics, "delay sync early late"),
    SettingEntry("Clear cached lyrics", SettingsCategory.Lyrics, "cache refresh refetch"),
    SettingEntry("Hide explicit content", SettingsCategory.Content, "explicit filter clean"),
    SettingEntry("Content language", SettingsCategory.Content, "language region locale youtube"),
    SettingEntry("Content country", SettingsCategory.Content, "country region locale youtube"),
    SettingEntry("Audio normalisation", SettingsCategory.Player, "loudness volume normalize normalization"),
    SettingEntry("Skip songs that cannot play", SettingsCategory.Player, "error skip unavailable"),
    SettingEntry("Remember the queue", SettingsCategory.Player, "persistent queue restore resume"),
    SettingEntry("Count a play after", SettingsCategory.Player, "minimum playback duration history stats"),
    SettingEntry("Sleep timer fade out", SettingsCategory.Player, "sleep fade"),
    SettingEntry("Sleep timer default time", SettingsCategory.Player, "sleep default minutes"),
    SettingEntry("LRCLIB", SettingsCategory.Lyrics, "lyrics provider lrclib"),
    SettingEntry("KuGou", SettingsCategory.Lyrics, "lyrics provider kugou"),
    SettingEntry("Sign in", SettingsCategory.Account, "login youtube google cookie firefox browser account"),
    SettingEntry("Discord", SettingsCategory.Account, "rich presence rpc status token"),
    SettingEntry("Listen Together", SettingsCategory.ListenTogether, "sync share lan network party"),
    SettingEntry("Music folders", SettingsCategory.Local, "local files folder mp3 flac scan computer"),
    SettingEntry("Rescan", SettingsCategory.Local, "local files refresh scan folders"),
    SettingEntry("Downloads", SettingsCategory.Storage, "offline delete space disk"),
    SettingEntry("Keep listening history", SettingsCategory.Privacy, "history recent stats record"),
    SettingEntry("Clear listening history", SettingsCategory.Privacy, "history delete stats reset"),
    SettingEntry("Search history", SettingsCategory.Privacy, "search recent queries clear"),
    SettingEntry("Import from a phone backup", SettingsCategory.Backup, "restore backup android zip"),
    SettingEntry("Settings profiles", SettingsCategory.Backup, "profile share theme export import equaliser"),
    SettingEntry("Back up library", SettingsCategory.Backup, "export backup save"),
    SettingEntry("Where the library is stored", SettingsCategory.Backup, "database file path backup sqlite"),
    SettingEntry("Version", SettingsCategory.About, "about github"),
    SettingEntry("Draw without the graphics card", SettingsCategory.About, "memory ram gpu software rendering performance"),
)

/** YouTube's content languages and countries the phone offers most, by code and name. */
internal val CONTENT_LANGUAGES = listOf(
    "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French", "de" to "German",
    "it" to "Italian", "nl" to "Dutch", "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic",
    "hi" to "Hindi", "id" to "Indonesian", "ms" to "Malay", "th" to "Thai", "vi" to "Vietnamese",
    "ja" to "Japanese", "ko" to "Korean", "zh-CN" to "Chinese (Simplified)", "zh-TW" to "Chinese (Traditional)",
)
internal val CONTENT_COUNTRIES = listOf(
    "US" to "United States", "GB" to "United Kingdom", "CA" to "Canada", "AU" to "Australia",
    "SG" to "Singapore", "MY" to "Malaysia", "ID" to "Indonesia", "PH" to "Philippines", "IN" to "India",
    "JP" to "Japan", "KR" to "South Korea", "TW" to "Taiwan", "HK" to "Hong Kong", "CN" to "China",
    "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "NL" to "Netherlands",
    "BR" to "Brazil", "MX" to "Mexico", "TR" to "Turkey", "RU" to "Russia",
)

/** Settings whose title or keywords contain every word of [query]. */
internal fun searchSettings(query: String): List<SettingEntry> {
    val words = query.trim().lowercase().split(" ").filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return SETTING_ENTRIES.filter { entry ->
        words.all { word ->
            entry.title.lowercase().contains(word) || entry.keywords.contains(word) ||
                entry.category.label.lowercase().contains(word)
        }
    }
}

/**
 * Settings, the way the phone lays them out: a list of categories, and the chosen one beside it.
 *
 * Side by side rather than drilled into, because a desktop window has the width for both - picking
 * another category is one click, not back-and-in. The search box above the list searches every
 * setting by name and by the words someone might reach for instead, and a result opens its category.
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
    /** After a profile is imported - see ProfilesSection. */
    onProfileApplied: () -> Unit = {},
    /** The account sync, for Account and sync's options. Null hides them. */
    sync: YouTubeSync? = null,
) {
    var category by remember { mutableStateOf(SettingsCategory.Appearance) }
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { searchSettings(query) }

    Row(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(280.dp).fillMaxHeight().padding(top = 4.dp, end = 12.dp)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search settings") },
                singleLine = true,
                shape = CircleShape,
                leadingIcon = { Icon(OuterTuneIcons.search, null, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(OuterTuneIcons.close, "Clear", modifier = Modifier.size(18.dp)) } }
                } else {
                    null
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.typingField().fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (query.isNotBlank()) {
                    if (matches.isEmpty()) {
                        Text(
                            "Nothing matches \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    matches.forEach { entry ->
                        CategoryItem(
                            title = entry.title,
                            subtitle = entry.category.label,
                            icon = entry.category.icon(),
                            selected = false,
                            onClick = {
                                category = entry.category
                                query = ""
                            },
                        )
                    }
                } else {
                    SettingsCategory.entries.forEach { item ->
                        CategoryItem(
                            title = item.label,
                            subtitle = item.blurb,
                            icon = item.icon(),
                            selected = item == category,
                            onClick = { category = item },
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                category.label,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            when (category) {
                SettingsCategory.Appearance -> AppearanceSettings(settings)
                SettingsCategory.Player -> PlayerSettings(settings)
                SettingsCategory.Lyrics -> LyricsSettings(settings, onClearLyricsCache)
                SettingsCategory.Content -> ContentSettings(settings)
                SettingsCategory.Account -> {
                    Section("YouTube Music") { AccountPane(account = account) }
                    AccountSyncSettings(settings, account, sync)
                    // With the other accounts, as on the phone.
                    DiscordSettings(settings)
                }
                SettingsCategory.ListenTogether -> ListenTogetherSettings(settings, listenTogether)
                SettingsCategory.Local -> LocalMusicSettings(settings, library)
                SettingsCategory.Storage -> DownloadSettings(downloads, onDownloadsChanged)
                SettingsCategory.Privacy -> PrivacySettings(settings, library, account)
                SettingsCategory.Backup -> BackupSettings(settings, library, libraryPath, onProfileApplied)
                SettingsCategory.Keyboard -> KeyboardSettings(settings)
                SettingsCategory.About -> AboutSettings(settings)
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CategoryItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppearanceSettings(settings: Settings) {
    Section("Theme") {
        SettingChoice(
            title = "Theme",
            icon = OuterTuneIcons.darkMode,
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelect = { settings.themeMode = it },
        )
        SettingSwitch(
            title = "Pure black",
            subtitle = "True black behind everything in dark mode.",
            checked = settings.pureBlack,
            onCheckedChange = { settings.pureBlack = it },
            icon = OuterTuneIcons.darkMode,
        )
        SettingSwitch(
            title = "Colour the app from the artwork",
            subtitle = "The window takes its palette from the cover of what is playing.",
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
            divider = false,
        )
    }
    Section("Text") {
        FontSetting(settings)
        SettingSlider(
            title = "Text size",
            icon = OuterTuneIcons.textRotationAngledown,
            value = settings.textScale,
            range = 80..140,
            step = 5,
            format = { "$it%" },
            default = 100,
            onChange = { settings.textScale = it },
            divider = false,
        )
    }
    Section("Layout") {
        SettingChoice(
            title = "Player background",
            icon = OuterTuneIcons.blurOn,
            options = BackgroundStyle.entries,
            selected = settings.background,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.background = it },
        )
        if (settings.background == BackgroundStyle.Ferrofluid) {
            SettingChoice(
                title = "Ferrofluid reactivity",
                icon = OuterTuneIcons.graphicEq,
                options = FerroReactivity.entries,
                selected = settings.ferroReactivity,
                label = { it.label },
                onSelect = { settings.ferroReactivity = it },
            )
        }
        PlayerLayoutSetting(settings)
        var choosingRow by remember { mutableStateOf(false) }
        SettingRow(
            title = "Song rows",
            subtitle = (RowButton.decode(settings.rowButtons).map { it.label } + if (settings.rowMenu) listOf("⋮ menu") else emptyList())
                .joinToString(", ").ifEmpty { "Nothing - right-click a song for its menu" },
            icon = OuterTuneIcons.moreVert,
            onClick = { choosingRow = true },
        )
        if (choosingRow) {
            RowButtonsDialog(
                current = RowButton.decode(settings.rowButtons),
                menu = settings.rowMenu,
                onSave = { buttons, menu ->
                    settings.rowButtons = RowButton.encode(buttons)
                    settings.rowMenu = menu
                    choosingRow = false
                },
                onDismiss = { choosingRow = false },
            )
        }
        var choosingTabs by remember { mutableStateOf(false) }
        SettingRow(
            title = "Tabs",
            subtitle = NavTab.decode(settings.tabs).joinToString(", ") { it.label },
            icon = OuterTuneIcons.libraryMusic,
            onClick = { choosingTabs = true },
        )
        if (choosingTabs) {
            TabsDialog(
                current = NavTab.decode(settings.tabs),
                onSave = {
                    settings.tabs = NavTab.encode(it)
                    if (settings.startTab !in it) settings.startTab = it.first()
                    choosingTabs = false
                },
                onDismiss = { choosingTabs = false },
            )
        }
        SettingChoice(
            title = "Open on",
            icon = OuterTuneIcons.home,
            options = NavTab.decode(settings.tabs),
            selected = settings.startTab,
            label = { it.label },
            onSelect = { settings.startTab = it },
            divider = false,
        )
    }
}

@Composable
private fun PlayerSettings(settings: Settings) {
    Section("Player") {
        SettingChoice(
            title = "Scroll in the player",
            icon = OuterTuneIcons.graphicEq,
            options = PlayerScroll.entries,
            selected = settings.playerScroll,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.playerScroll = it },
        )
        SettingSwitch(
            title = "Seek buttons",
            subtitle = "Skip back and forward buttons either side of play.",
            checked = settings.showSeekButtons,
            onCheckedChange = { settings.showSeekButtons = it },
            icon = OuterTuneIcons.fastForward,
        )
        if (settings.showSeekButtons) {
            SettingSlider(
                title = "Seek by",
                subtitle = "How far those buttons move.",
                icon = OuterTuneIcons.fastForward,
                value = settings.seekIncrementSeconds,
                range = 5..60,
                step = 5,
                format = { "$it s" },
                onChange = { settings.seekIncrementSeconds = it },
                default = 5,
            )
        }
        SettingSwitch(
            title = "Visualiser",
            subtitle = "Spectrum bars above the seek bar.",
            checked = settings.showVisualizer,
            onCheckedChange = { settings.showVisualizer = it },
            icon = OuterTuneIcons.graphicEq,
        )
        var arranging by remember { mutableStateOf(false) }
        SettingRow(
            title = "Player buttons",
            subtitle = PlayerButtonKind.decode(settings.playerButtons).joinToString(", ") { it.label },
            icon = OuterTuneIcons.moreVert,
            onClick = { arranging = true },
        )
        if (arranging) {
            PlayerButtonsDialog(
                current = PlayerButtonKind.decode(settings.playerButtons),
                onSave = {
                    settings.playerButtons = PlayerButtonKind.encode(it)
                    arranging = false
                },
                onDismiss = { arranging = false },
            )
        }
        SettingSwitch(
            title = "Separate buttons for local files",
            subtitle = "Files on this computer get their own player buttons. Like, download and other YouTube-only buttons never show for them.",
            checked = settings.localPlayerButtonsOwn,
            onCheckedChange = { settings.localPlayerButtonsOwn = it },
            icon = OuterTuneIcons.sdCard,
        )
        if (settings.localPlayerButtonsOwn) {
            var arrangingLocal by remember { mutableStateOf(false) }
            SettingRow(
                title = "Local file player buttons",
                subtitle = PlayerButtonKind.decodeLocal(settings.localPlayerButtons).joinToString(", ") { it.label },
                icon = OuterTuneIcons.moreVert,
                onClick = { arrangingLocal = true },
            )
            if (arrangingLocal) {
                PlayerButtonsDialog(
                    title = "Local file player buttons",
                    current = PlayerButtonKind.decodeLocal(settings.localPlayerButtons),
                    available = PlayerButtonKind.entries.filter { it.forLocalFiles },
                    defaults = PlayerButtonKind.decodeLocal(""),
                    onSave = {
                        settings.localPlayerButtons = PlayerButtonKind.encode(it)
                        arrangingLocal = false
                    },
                    onDismiss = { arrangingLocal = false },
                )
            }
        }
        SettingChoice(
            title = "Seek bar",
            icon = OuterTuneIcons.palette,
            options = SeekBarStyle.entries,
            selected = settings.sliderStyle,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.sliderStyle = it },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SettingRow(
            title = "Equaliser",
            subtitle = "Opened from the player: the equaliser button, or scroll up at the top of it.",
            icon = OuterTuneIcons.equalizer,
        )
    }
    // Everything about the queue in one place: how its handle looks on the player, and what is kept.
    Section("Queue") {
        SettingSwitch(
            title = "Show queue title",
            subtitle = "Name the queue beside the queue handle on the player.",
            checked = settings.showQueueTitle,
            onCheckedChange = { settings.showQueueTitle = it },
            icon = OuterTuneIcons.queueMusic,
        )
        SettingSwitch(
            title = "Queue handle in the title bar",
            subtitle = "In a wide window, beside the window buttons. Off keeps it along the bottom of the player.",
            checked = settings.queueHandleTop,
            onCheckedChange = { settings.queueHandleTop = it },
            icon = OuterTuneIcons.queueMusic,
        )
        SettingSwitch(
            title = "Remember the queue",
            subtitle = "Keeps the queue, and where you were in it, for next time.",
            checked = settings.persistentQueue,
            onCheckedChange = { settings.persistentQueue = it },
            icon = OuterTuneIcons.queueMusic,
        )
        SettingSlider(
            title = "Queues to keep",
            subtitle = "Playing something new keeps the queue it replaces, to switch back to from the queue's title.",
            icon = OuterTuneIcons.queueMusic,
            value = settings.maxQueues,
            range = 1..50,
            step = 1,
            format = { "$it" },
            default = 19,
            onChange = { settings.maxQueues = it },
            divider = false,
        )
    }
    Section("Audio") {
        SettingChoice(
            title = "Audio quality",
            icon = OuterTuneIcons.graphicEq,
            options = AudioQuality.entries,
            selected = settings.audioQuality,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.audioQuality = it },
        )
        SettingSwitch(
            title = "Spectrum behind the equaliser",
            subtitle = "Draws what is actually playing behind the response curve, so a cut lands where the energy is.",
            checked = settings.eqSpectrum,
            onCheckedChange = { settings.eqSpectrum = it },
            icon = OuterTuneIcons.graphicEq,
        )
        SettingSwitch(
            title = "Audio normalisation",
            subtitle = "Evens out volume between songs using YouTube's loudness figure. Only ever turns down.",
            checked = settings.audioNormalisation,
            onCheckedChange = { settings.audioNormalisation = it },
            icon = OuterTuneIcons.graphicEq,
        )
        SettingSwitch(
            title = "Turn up for EQ cuts",
            subtitle = "When equaliser bands are turned down, raise the volume by the loudness the cuts take away, so the song stays about as loud as it was.",
            checked = settings.eqCutMakeup,
            onCheckedChange = { settings.eqCutMakeup = it },
            icon = OuterTuneIcons.equalizer,
        )
        SettingSwitch(
            title = "Skip songs that cannot play",
            subtitle = "Moves on to the next song instead of stopping.",
            checked = settings.skipOnError,
            onCheckedChange = { settings.skipOnError = it },
            icon = OuterTuneIcons.skipNext,
        )
        SettingSlider(
            title = "Count a play after",
            subtitle = "How much of a song must be heard before it goes into history and Stats.",
            icon = OuterTuneIcons.history,
            value = settings.minPlayPercent,
            range = 0..100,
            step = 10,
            format = { "$it%" },
            onChange = { settings.minPlayPercent = it },
            default = 30,
            divider = false,
        )
    }
    Section("Sleep timer") {
        SettingSwitch(
            title = "Fade out",
            subtitle = "The end of the timer fades to silence instead of stopping mid-phrase.",
            checked = settings.sleepFade,
            onCheckedChange = { settings.sleepFade = it },
            icon = OuterTuneIcons.bedtime,
        )
        if (settings.sleepFade) {
            SettingSlider(
                title = "Fade length",
                subtitle = "How long the music takes to reach silence.",
                icon = OuterTuneIcons.bedtime,
                value = settings.sleepFadeSeconds,
                range = 5..120,
                step = 5,
                format = { "$it s" },
                onChange = { settings.sleepFadeSeconds = it },
                default = 30,
            )
        }
        SettingSlider(
            title = "Default time",
            subtitle = "Offered first when the sleep timer is opened.",
            icon = OuterTuneIcons.timer,
            value = settings.sleepDefaultMinutes,
            range = 5..120,
            step = 5,
            format = { "$it min" },
            onChange = { settings.sleepDefaultMinutes = it },
            default = 30,
            divider = false,
        )
    }
}

@Composable
private fun LyricsSettings(settings: Settings, onClearLyricsCache: () -> Unit) {
    Section("Sources") {
        // The mode first, since it decides what follows: in my order, one list to tick and drag; on
        // automatic, where every provider is asked at once and order does not matter, which are on.
        SettingChoice(
            title = "Provider selection",
            icon = OuterTuneIcons.lyrics,
            options = LyricsFetchMode.entries,
            selected = settings.lyricsFetchMode,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.lyricsFetchMode = it },
        )
        if (settings.lyricsFetchMode == LyricsFetchMode.InMyOrder) {
            var choosingProviders by remember { mutableStateOf(false) }
            SettingRow(
                title = "Provider priority",
                subtitle = LyricsSource.ordered(settings.lyricsProviderOrder)
                    .filter { settings.isEnabled(it) }
                    .joinToString(", ") { it.label }
                    .ifEmpty { "None - lyrics only from files on this computer" },
                icon = OuterTuneIcons.dragHandle,
                onClick = { choosingProviders = true },
            )
            if (choosingProviders) LyricsProvidersDialog(settings, onDismiss = { choosingProviders = false })
        } else {
            LyricsSource.ordered(settings.lyricsProviderOrder).forEach { source ->
                SettingSwitch(
                    title = source.label,
                    subtitle = source.blurb,
                    checked = settings.isEnabled(source),
                    onCheckedChange = { settings.setEnabled(source, it) },
                    icon = OuterTuneIcons.lyrics,
                )
            }
        }
        SettingSwitch(
            title = "Estimate word timings",
            subtitle = "Sings line-timed lyrics word by word too, paced by syllables and pausing at punctuation.",
            checked = settings.estimateWordTimings,
            onCheckedChange = { settings.estimateWordTimings = it },
            icon = OuterTuneIcons.textRotationAngledown,
        )
        SettingRow(
            title = "Clear cached lyrics",
            subtitle = "For the song playing now.",
            icon = OuterTuneIcons.delete,
            onClick = onClearLyricsCache,
        )
    }
    Section("Display") {
        SettingSwitch(
            title = "Click the cover for lyrics",
            subtitle = "Swaps the album art for the words, and back.",
            checked = settings.lyricsOnCoverClick,
            onCheckedChange = { settings.lyricsOnCoverClick = it },
            icon = OuterTuneIcons.lyrics,
        )
        SettingSlider(
            title = "Lyrics text size",
            icon = OuterTuneIcons.textRotationAngledown,
            value = settings.lyricsTextScale,
            range = 70..160,
            step = 10,
            format = { "$it%" },
            default = 100,
            onChange = { settings.lyricsTextScale = it },
        )
        SettingChoice(
            title = "Lyrics alignment",
            icon = OuterTuneIcons.lyrics,
            options = LyricsAlignment.entries,
            selected = settings.lyricsAlignment,
            label = { it.label },
            onSelect = { settings.lyricsAlignment = it },
        )
        SettingChoice(
            title = "Word highlight",
            icon = OuterTuneIcons.textRotationAngledown,
            options = LyricsHighlight.entries,
            selected = settings.lyricsHighlight,
            label = { it.label },
            description = { it.description },
            onSelect = { settings.lyricsHighlight = it },
        )
        SettingSwitch(
            title = "Show lyrics type",
            subtitle = "A small tag by the lyrics controls: word-synced, line-synced, estimated or unsynced.",
            checked = settings.lyricsShowKind,
            onCheckedChange = { settings.lyricsShowKind = it },
            icon = OuterTuneIcons.info,
        )
        SettingSlider(
            title = "Lyrics timing offset",
            subtitle = "Shifts every line. Positive is later.",
            icon = OuterTuneIcons.timer,
            value = settings.lyricsOffsetMs,
            range = -3000..3000,
            step = 50,
            format = { "${if (it > 0) "+" else ""}$it ms" },
            default = 0,
            onChange = { settings.lyricsOffsetMs = it },
            divider = false,
        )
    }
}

@Composable
private fun ContentSettings(settings: Settings) {
    Section("Region") {
        SettingChoice(
            title = "Content language",
            icon = OuterTuneIcons.info,
            options = CONTENT_LANGUAGES.map { it.first },
            selected = settings.contentLanguage,
            label = { code -> CONTENT_LANGUAGES.firstOrNull { it.first == code }?.second ?: code },
            onSelect = { settings.contentLanguage = it },
        )
        SettingChoice(
            title = "Content country",
            icon = OuterTuneIcons.home,
            options = CONTENT_COUNTRIES.map { it.first },
            selected = settings.contentCountry,
            label = { code -> CONTENT_COUNTRIES.firstOrNull { it.first == code }?.second ?: code },
            onSelect = { settings.contentCountry = it },
            divider = false,
        )
    }
    Section("Filters") {
        SettingSwitch(
            title = "Hide explicit content",
            subtitle = "Leaves explicit songs out of search, Home and artist pages.",
            checked = settings.hideExplicit,
            onCheckedChange = { settings.hideExplicit = it },
            icon = OuterTuneIcons.info,
            divider = false,
        )
    }
}

@Composable
private fun DiscordSettings(settings: Settings) {
    val scope = rememberCoroutineScope()
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
                subtitle = "Shows the song you are playing as a card on your Discord profile.",
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
                    modifier = Modifier.typingField().fillMaxWidth(),
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
}

@Composable
private fun ListenTogetherSettings(settings: Settings, listenTogether: ListenTogetherManager) {
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

        // The phone carries this and the desktop did not, which mattered more here than there:
        // "listen together" sounds like it streams audio between machines, and what it actually
        // does - each device fetching the song itself and only keeping time with the host - is
        // both more private and less obvious.
        SettingRow(
            title = "How it works",
            subtitle = "Everything stays on your local network — nothing is sent over the " +
                "internet. Audio is not streamed between devices; each one plays the song " +
                "itself and keeps time with the host.",
            icon = OuterTuneIcons.wifi,
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
                    "How far ahead of the host to aim. Set by ear - it corrects for this " +
                        "device's own output latency, which nothing here can measure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RotaryDial(
                value = settings.listenTogetherOffsetMs.toFloat(),
                onValueChange = { settings.listenTogetherOffsetMs = it.toInt() },
                valueRange = -3000f..3000f,
                color = if (settings.colourByValue) {
                    ValueGradient.forValue(settings.listenTogetherOffsetMs.toFloat(), -3000f..3000f)
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
}

/**
 * The folders whose music is in the library. Added with the system's folder picker - several at
 * once - removed one by one, and rescanned on demand; a scan also runs at every start.
 */
@Composable
private fun LocalMusicSettings(settings: Settings, library: LibraryStore) {
    val scope = rememberCoroutineScope()
    val scanning by LocalMusic.scanning.collectAsState()
    val status by LocalMusic.status.collectAsState()
    val folders = settings.localFolders.lines().filter { it.isNotBlank() }

    fun rescan(list: List<String>) {
        scope.launch { LocalMusic.scan(list.map { java.io.File(it) }, library) }
    }

    Section("Local media") {
        SettingSwitch(
            title = "Use local media",
            subtitle = "Reads the folders below. Off leaves them alone entirely.",
            checked = settings.localEnabled,
            onCheckedChange = { settings.localEnabled = it },
            icon = OuterTuneIcons.musicNote,
        )
        SettingSwitch(
            title = "Rescan at every start",
            subtitle = "Picks up songs added or changed since last time. Off still loads what is already known.",
            checked = settings.localScanOnStart,
            onCheckedChange = { settings.localScanOnStart = it },
            icon = OuterTuneIcons.restore,
            divider = false,
        )
    }
    Section("Music folders") {
        if (folders.isEmpty()) {
            Text(
                "No folders yet. Add the folders your music is in, and it appears under Library, " +
                    "On this computer - with its own artists, cover art and lyrics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        folders.forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Icon(
                    OuterTuneIcons.musicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                )
                Text(folder, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val remaining = folders - folder
                    settings.localFolders = remaining.joinToString("\n")
                    rescan(remaining)
                }) {
                    Icon(OuterTuneIcons.close, contentDescription = "Remove $folder", modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch {
                    val picked = FilePicker.pickFolders("Add music folders").map { it.absolutePath }
                    if (picked.isEmpty()) return@launch
                    val all = (folders + picked).distinct()
                    settings.localFolders = all.joinToString("\n")
                    LocalMusic.scan(all.map { java.io.File(it) }, library)
                }
            }) { Text("Add folder…") }
            TextButton(onClick = { rescan(folders) }, enabled = folders.isNotEmpty() && !scanning) { Text("Rescan") }
            if (scanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
    // Files taken out with "Remove from library", to put back. Restoring rescans, which is what
    // brings a file in.
    val removed = settings.localRemoved.lines().filter { it.isNotBlank() }
    if (removed.isNotEmpty()) {
        Section("Removed from library") {
            removed.forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(
                        OuterTuneIcons.musicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp).size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(java.io.File(path).nameWithoutExtension, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = {
                        val remaining = removed - path
                        settings.localRemoved = remaining.joinToString("\n")
                        LocalMusic.removed = LocalMusic.removedFrom(settings.localRemoved)
                        rescan(folders)
                    }, enabled = !scanning) { Text("Restore") }
                }
            }
        }
    }
}

@Composable
private fun DownloadSettings(downloads: Downloads, onDownloadsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
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
}

@Composable
private fun BackupSettings(settings: Settings, library: LibraryStore, libraryPath: String, onProfileApplied: () -> Unit) {
    val scope = rememberCoroutineScope()

    Section("Automatic backups") {
        // Counted from the folder rather than from a stored timestamp - see AutoBackup. What is on
        // disk is what can actually be restored, so it is what the screen should report.
        val existing = remember(settings.autoBackup, settings.autoBackupKeep, settings.autoBackupHours) {
            AutoBackup.existing(AutoBackup.folder())
        }
        SettingSwitch(
            title = "Back up automatically",
            subtitle = "Keeps copies of the library beside it, so a bad import or a lost file is not the end of it.",
            checked = settings.autoBackup,
            onCheckedChange = { settings.autoBackup = it },
            icon = OuterTuneIcons.restore,
            divider = settings.autoBackup,
        )
        if (settings.autoBackup) {
            SettingSlider(
                title = "How often",
                icon = OuterTuneIcons.timer,
                value = settings.autoBackupHours,
                range = 1..168,
                step = 1,
                format = { if (it % 24 == 0) "every ${it / 24} day${if (it > 24) "s" else ""}" else "every $it h" },
                onChange = { settings.autoBackupHours = it },
                default = 24,
            )
            SettingSlider(
                title = "How many to keep",
                subtitle = "The oldest is deleted once there are more than this.",
                icon = OuterTuneIcons.delete,
                value = settings.autoBackupKeep,
                range = 1..20,
                step = 1,
                format = { "$it" },
                onChange = { settings.autoBackupKeep = it },
                default = 5,
            )
            SettingRow(
                title = if (existing.isEmpty()) "No automatic backups yet" else "${existing.size} kept",
                subtitle = AutoBackup.folder().absolutePath,
                icon = OuterTuneIcons.download,
            )
        }
    }

    Section("Backup and restore") {

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
            Text("Back up and restore", style = MaterialTheme.typography.bodyLarge)
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
                    scope.launch {
                        val file = FilePicker.pickFile("Choose a backup", "OuterTune backup", "*.backup;*.zip")
                            ?: return@launch
                        importing = true
                        importResult = null
                        // A desktop backup replaces the library at the next start; a phone backup
                        // is merged into this one now.
                        importResult = if (DesktopBackup.isDesktopBackup(file)) {
                            runCatching { DesktopBackup.stageRestore(file) }.fold(
                                { ImportSummary(error = null, restoreStaged = true) },
                                { ImportSummary(error = "Could not read that backup: ${it.message}") },
                            )
                        } else {
                            BackupImport.importFrom(file, library)
                        }
                        importing = false
                    }
                },
            ) {
                Text(if (importing) "Importing…" else "Choose backup…")
            }
                var exporting by remember { mutableStateOf(false) }
                TextButton(
                    enabled = !exporting,
                    onClick = {
                        scope.launch {
                            val folder = FilePicker.pickFolders("Where to save the backup").firstOrNull() ?: return@launch
                            exporting = true
                            importResult = runCatching { DesktopBackup.export(library, folder) }.fold(
                                { ImportSummary(exported = it.absolutePath) },
                                { ImportSummary(error = "Could not write the backup: ${it.message}") },
                            )
                            exporting = false
                        }
                    },
                ) {
                    Text(if (exporting) "Backing up…" else "Back up library…")
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
        ProfilesSection(settings, onApplied = onProfileApplied)

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
}

@Composable
private fun PrivacySettings(settings: Settings, library: LibraryStore, account: Account) {
    // Grouped the way the phone groups them: each kind of history owns its switch and its clear
    // button, rather than the switches sitting in one place and the clearing in another. Pairing
    // them is what makes "stop recording this, and get rid of what was recorded" one thought.
    val accountState by account.state.collectAsState()
    val signedIn = accountState is AccountState.SignedIn

    Section("Listening history") {
        SettingSwitch(
            title = "Keep listening history",
            subtitle = "Records what has been played, which is what the recent list and Stats are made of.",
            checked = settings.keepHistory,
            onCheckedChange = { settings.keepHistory = it },
            icon = OuterTuneIcons.history,
        )
        // Only while signed in, and only while there is a local history to mirror. Offered against
        // a signed-out app it would be a switch that cannot do anything, which reads as broken
        // rather than as not applicable - so it is absent instead of disabled.
        if (signedIn) {
            SettingSwitch(
                title = "Share listening history with YouTube Music",
                subtitle = "Plays here also count on your account, so they reach your recommendations and other devices.",
                checked = settings.shareHistoryWithYtm && settings.keepHistory,
                onCheckedChange = { settings.shareHistoryWithYtm = it },
                icon = OuterTuneIcons.cast,
            )
            SettingSwitch(
                title = "Count plays from other devices",
                subtitle = "Stats adds plays from your YouTube Music history - other devices, the app, the web - without counting this computer's twice.",
                checked = settings.statsIncludeRemote,
                onCheckedChange = { settings.statsIncludeRemote = it },
                icon = OuterTuneIcons.history,
            )
        }
        var clearingPlays by remember { mutableStateOf(false) }
        SettingRow(
            title = if (clearingPlays) "Click again to clear listening history" else "Clear listening history",
            subtitle = "Recently played and every count Stats is made of. Songs and playlists stay.",
            icon = OuterTuneIcons.delete,
            tint = MaterialTheme.colorScheme.error,
            onClick = {
                if (clearingPlays) {
                    library.clearHistory()
                    clearingPlays = false
                } else {
                    clearingPlays = true
                }
            },
        )
    }

    Section("Search history") {
        SettingSwitch(
            title = "Keep search history",
            subtitle = "Recent searches appear under the search box while it is empty.",
            checked = settings.keepSearchHistory,
            onCheckedChange = { settings.keepSearchHistory = it },
            icon = OuterTuneIcons.search,
        )
        var clearingSearches by remember { mutableStateOf(false) }
        SettingRow(
            title = if (clearingSearches) "Click again to clear search history" else "Clear search history",
            icon = OuterTuneIcons.delete,
            tint = MaterialTheme.colorScheme.error,
            onClick = {
                if (clearingSearches) {
                    settings.searchHistory = ""
                    clearingSearches = false
                } else {
                    clearingSearches = true
                }
            },
        )
    }

    Section("Proxy") {
        SettingSwitch(
            title = "Use a proxy",
            subtitle = "Sends YouTube Music requests through a proxy. Takes effect straight away.",
            checked = settings.proxyEnabled,
            onCheckedChange = { settings.proxyEnabled = it },
            icon = OuterTuneIcons.devices,
            divider = settings.proxyEnabled,
        )
        if (settings.proxyEnabled) {
            SettingChoice(
                title = "Proxy type",
                icon = OuterTuneIcons.devices,
                options = ProxyKind.entries,
                selected = settings.proxyType,
                label = { it.label },
                onSelect = { settings.proxyType = it },
            )
            SettingTextField(
                title = "Proxy address",
                subtitle = "As host:port - for example 127.0.0.1:8080.",
                icon = OuterTuneIcons.devices,
                value = settings.proxyUrl,
                placeholder = "host:port",
                onValueChange = { settings.proxyUrl = it },
                divider = false,
            )
        }
    }
}

@Composable
private fun AboutSettings(settings: Settings) {
    val scope = rememberCoroutineScope()
    val update by DesktopUpdater.state.collectAsState()
    Section("OuterTune for desktop") {
        var onProcessor by remember { mutableStateOf(RenderMode.chosen()) }
        SettingSwitch(
            title = "Draw without the graphics card",
            subtitle = "Uses about 130MB less memory; scrolling and animated backgrounds cost more processor time." +
                if (onProcessor != RenderMode.onProcessor) " Takes effect when OuterTune next starts." else "",
            checked = onProcessor,
            onCheckedChange = {
                RenderMode.choose(it)
                onProcessor = it
            },
            icon = OuterTuneIcons.tune,
        )
        SettingRow(
            title = "Version",
            subtitle = DesktopVersion.NAME,
            icon = OuterTuneIcons.info,
        )
        SettingRow(
            title = when (val state = update) {
                is DesktopUpdater.State.Available -> "Download OuterTune ${state.update.name}"
                is DesktopUpdater.State.Ready -> "Install OuterTune ${state.update.name}"
                else -> "Check for updates"
            },
            subtitle = when (val state = update) {
                DesktopUpdater.State.Idle -> "From the project's releases on GitHub."
                DesktopUpdater.State.Checking -> "Checking…"
                DesktopUpdater.State.UpToDate -> "This is the latest version."
                is DesktopUpdater.State.Available -> "A new version is available."
                is DesktopUpdater.State.Downloading -> "Downloading… ${(state.progress * 100).toInt()}%"
                is DesktopUpdater.State.Ready -> "Downloaded. Installing closes OuterTune and opens it again."
                is DesktopUpdater.State.Failed -> state.reason
            },
            icon = OuterTuneIcons.restore,
            onClick = {
                when (val state = update) {
                    is DesktopUpdater.State.Available -> scope.launch { DesktopUpdater.download(state.update) }
                    is DesktopUpdater.State.Ready -> installUpdateAndQuit(state.installer)
                    is DesktopUpdater.State.Checking, is DesktopUpdater.State.Downloading -> Unit
                    else -> scope.launch {
                        settings.updateLastCheck = System.currentTimeMillis().toString()
                        DesktopUpdater.check()
                    }
                }
            },
        )
        SettingSwitch(
            title = "Check for updates automatically",
            subtitle = "When OuterTune starts, at most every few hours.",
            checked = settings.updateAutoCheck,
            onCheckedChange = { settings.updateAutoCheck = it },
            icon = OuterTuneIcons.restore,
        )
        SettingSwitch(
            title = "Download updates automatically",
            subtitle = "Ready to install when found. Installing still waits for you.",
            checked = settings.updateAutoDownload,
            onCheckedChange = { settings.updateAutoDownload = it },
            icon = OuterTuneIcons.download,
        )
        SettingRow(
            title = "Source code",
            subtitle = "github.com/notK50BML/OuterTune",
            icon = OuterTuneIcons.musicNote,
            onClick = {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/notK50BML/OuterTune")) }
            },
        )
    }
}

/**
 * A choice among a few options, shown as its current value and opened as a menu - the phone's
 * ListPreference. Radio lists laid out inline made every multiple-choice setting a block of five
 * rows, which is most of why this screen used to look uneven.
 */
@Composable
private fun <T> SettingChoice(
    title: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    description: ((T) -> String)? = null,
    divider: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            title = title,
            subtitle = label(selected),
            icon = icon,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(option), fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal)
                            description?.let {
                                Text(
                                    it(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { RadioButton(selected = option == selected, onClick = null) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** A number on a slider, with its value shown beside the title. */
@Composable
private fun SettingSlider(
    title: String,
    icon: ImageVector,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    subtitle: String? = null,
    divider: Boolean = true,
    /** Where Reset goes back to. Null shows no Reset. */
    default: Int? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp).size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Plain clickable text, not a TextButton: a button's 40dp minimum height made the whole row
        // grow the moment the value left its default, so the card jumped as the slider moved.
        if (default != null && value != default) {
            Text(
                "Reset",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onChange(default) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range.first, range.last)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        modifier = Modifier.padding(start = 40.dp),
    )
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/**
 * The app's typeface: any font installed on this machine, picked from a searchable list in which
 * each name is set in its own face, so the list is its own preview.
 */
@Composable
private fun FontSetting(settings: Settings) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title = "Font",
        subtitle = settings.fontFamily.ifBlank { "Default" },
        icon = OuterTuneIcons.textRotationAngledown,
        onClick = { open = true },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    if (!open) return

    var filter by remember { mutableStateOf("") }
    var families by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { families = SystemFonts.load() }
    val shown = remember(filter, families) {
        families.orEmpty().filter { it.contains(filter.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        title = { Text("Font") },
        text = {
            Column(modifier = Modifier.width(420.dp).height(480.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(families?.let { "Search ${it.size} fonts" } ?: "Loading fonts…") },
                    singleLine = true,
                    modifier = Modifier.typingField().fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    item {
                        FontOption("Default", null, settings.fontFamily.isBlank()) { settings.fontFamily = "" }
                    }
                    items(shown, key = { it }) { name ->
                        FontOption(name, name, settings.fontFamily == name) { settings.fontFamily = name }
                    }
                }
            }
        },
    )
}

@Composable
private fun FontOption(label: String, family: String?, selected: Boolean, onClick: () -> Unit) {
    val fontFamily = remember(family) { family?.let { SystemFonts.family(it) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = if (fontFamily != null) MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily) else MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
    }
}


@Composable
private fun Section(title: String, content: @Composable ColumnScopeMarker.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ColumnScopeMarker.content()
    }
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
    divider: Boolean = true,
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
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/**
 * A row whose value is typed rather than chosen - the phone's `EditTextPreference`.
 *
 * Written straight through on every keystroke, with no Save button. Everything else on this screen
 * applies as it is changed, and a field that needed confirming would be the one control here that
 * silently discarded what was typed if the category was left.
 */
@Composable
private fun SettingTextField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    placeholder: String = "",
    divider: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            // Marked so the keyboard shortcuts stand aside while this has focus - without it,
            // typing a host would skip tracks and toggle shuffle on the way through.
            modifier = Modifier.width(220.dp).typingField(),
        )
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
        Column(modifier = Modifier.weight(1f)) {
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

/**
 * The rest of the phone's Account and sync, under the sign-in: the login token, browsing signed in,
 * and the sync - automatic or not, now, what it brings, read only or read write, and what wins.
 * Shown signed in, as on the phone; signed out there is nothing to sync.
 */
@Composable
private fun AccountSyncSettings(settings: Settings, account: Account, sync: YouTubeSync?) {
    val state by account.state.collectAsState()
    if (state !is AccountState.SignedIn) return
    val scope = rememberCoroutineScope()

    Section("Account") {
        SettingSwitch(
            title = "Use login for browsing content",
            subtitle = "Recommendations made for you, and premium-only albums on a Premium account. Off browses as a guest.",
            checked = settings.useLoginForBrowse,
            onCheckedChange = { settings.useLoginForBrowse = it },
            icon = OuterTuneIcons.accountCircle,
        )
        var tokenShown by remember { mutableStateOf(false) }
        SettingRow(
            title = if (tokenShown) "Hide login token" else "Show login token",
            subtitle = if (tokenShown) {
                YouTube.cookie?.let { if (it.length > 160) it.take(160) + "…" else it } ?: "None stored"
            } else {
                "For signing in on another computer: Account > paste it under \"Sign in\". Keep it private - it is your session."
            },
            icon = OuterTuneIcons.key,
            onClick = { tokenShown = !tokenShown },
        )
        if (tokenShown) SettingRow(
            title = "Copy login token",
            icon = OuterTuneIcons.share,
            onClick = { YouTube.cookie?.let(::copyToClipboard) },
        )
    }

    if (sync == null) return
    val syncing by sync.syncing.collectAsState()
    val status by sync.status.collectAsState()
    Section("Sync") {
        SettingSwitch(
            title = "Sync automatically",
            subtitle = "Syncs with your YouTube Music account when you sign in and each time the app starts.",
            checked = settings.syncAuto,
            onCheckedChange = { settings.syncAuto = it },
            icon = OuterTuneIcons.restore,
        )
        SettingRow(
            title = if (syncing) "Syncing…" else "Sync now",
            subtitle = status ?: "Brings in what is ticked below.",
            icon = OuterTuneIcons.restore,
            onClick = if (syncing) null else ({ scope.launch { sync.syncAll() } }),
        )
        val chosen = SyncKind.decode(settings.syncContent)
        Text(
            "What to sync",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        SyncKind.entries.forEach { kind ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        settings.syncContent = SyncKind.encode(if (kind in chosen) chosen - kind else chosen + kind)
                    }
                    .padding(horizontal = 8.dp),
            ) {
                androidx.compose.material3.Checkbox(checked = kind in chosen, onCheckedChange = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(kind.label)
            }
        }
        SettingChoice(
            title = "Sync mode",
            icon = OuterTuneIcons.restore,
            options = SyncMode.entries,
            selected = settings.syncMode,
            label = { it.label },
            onSelect = { settings.syncMode = it },
        )
        SettingChoice(
            title = "Conflict resolution",
            icon = OuterTuneIcons.restore,
            options = SyncConflict.entries,
            selected = settings.syncConflict,
            label = { it.label },
            onSelect = { settings.syncConflict = it },
        )
    }
}
