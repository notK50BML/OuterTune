package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoBackupDefaults
import com.dd3boh.outertune.constants.AutoBackupKeepCountKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.PauseSearchHistoryKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.writeAutoBackup
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ColumnScope.ListenHistoryFrag() {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    val (pauseListenHistory, onPauseListenHistoryChange) = rememberPreference(
        key = PauseListenHistoryKey,
        defaultValue = false
    )
    val (pauseRemoteListenHistory, onPauseRemoteListenHistoryChange) = rememberPreference(
        key = PauseRemoteListenHistoryKey,
        defaultValue = false
    )

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    var showClearListenHistoryDialog by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.pause_listen_history)) },
        icon = { Icon(Icons.Rounded.History, null) },
        checked = pauseListenHistory,
        onCheckedChange = onPauseListenHistoryChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.pause_remote_listen_history)) },
        icon = { Icon(Icons.Rounded.History, null) },
        checked = pauseRemoteListenHistory,
        onCheckedChange = onPauseRemoteListenHistoryChange,
        isEnabled = !pauseListenHistory && isLoggedIn
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_listen_history)) },
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        onClick = { showClearListenHistoryDialog = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearListenHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearListenHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_listen_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearListenHistoryDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearListenHistoryDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            // A safety net before something this irreversible: the same full
                            // backup a scheduled automatic backup would write, to the same place.
                            // Best-effort - a failed safety backup shouldn't block the clear the
                            // user already confirmed.
                            runCatching {
                                writeAutoBackup(
                                    context,
                                    database,
                                    context.dataStore.get(AutoBackupKeepCountKey, AutoBackupDefaults.KEEP_COUNT)
                                )
                            }
                            database.query {
                                clearListenHistory()
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
fun ColumnScope.SearchHistoryFrag() {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    val (pauseSearchHistory, onPauseSearchHistoryChange) = rememberPreference(
        key = PauseSearchHistoryKey,
        defaultValue = false
    )

    var showClearSearchHistoryDialog by remember {
        mutableStateOf(false)
    }

    SwitchPreference(
        title = { Text(stringResource(R.string.pause_search_history)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.ManageSearch, null) },
        checked = pauseSearchHistory,
        onCheckedChange = onPauseSearchHistoryChange
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_search_history)) },
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        onClick = { showClearSearchHistoryDialog = true }
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showClearSearchHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearSearchHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_search_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearSearchHistoryDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearSearchHistoryDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            // See ListenHistoryFrag's identical safety backup for why.
                            runCatching {
                                writeAutoBackup(
                                    context,
                                    database,
                                    context.dataStore.get(AutoBackupKeepCountKey, AutoBackupDefaults.KEEP_COUNT)
                                )
                            }
                            database.query {
                                clearSearchHistory()
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
