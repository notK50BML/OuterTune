/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Signing in.
 *
 * Three routes to one credential, offered in order of how little the user has to do. Firefox first
 * because for anyone using it there is nothing to do at all; the file second; pasting last, for
 * people who already have a cookie or use a browser this cannot read.
 *
 * What is deliberately not here is an explanation of why it works this way. The reasons are real -
 * see SIGN-IN.md - and none of them are the user's problem.
 */
@Composable
fun AccountPane(
    account: Account,
    modifier: Modifier = Modifier,
) {
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    // Listed once rather than on every recomposition: it touches the filesystem, and this screen
    // recomposes whenever the sign-in state changes.
    val firefoxProfiles = remember { runCatching { FirefoxCookies.profiles() }.getOrDefault(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        when (val current = state) {
            is AccountState.SignedIn -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(current.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        current.email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { account.signOut() }) { Text("Sign out") }
                    }
                }
                return@Column
            }

            is AccountState.Expired -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        current.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            AccountState.SignedOut -> {
                Text(
                    "Sign in to see your library, liked songs and playlists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Only offered if Firefox is actually there. An option that cannot work is worse than no
        // option, because it reads as the thing that was supposed to work.
        if (firefoxProfiles.isNotEmpty()) {
            Text("From Firefox", style = MaterialTheme.typography.labelLarge)
            Text(
                "Sign in to YouTube Music in Firefox first, then pick the profile you used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            firefoxProfiles.take(4).forEach { profile ->
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            account.signInFromFirefox(profile)
                            busy = false
                        }
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                ) { Text(profile.name) }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("From a cookies.txt file", style = MaterialTheme.typography.labelLarge)
        Text(
            "Export your cookies with a browser extension, then choose the file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            enabled = !busy,
            onClick = {
                // AWT's dialog rather than a Compose one: this is the OS file picker, which is what
                // people recognise, and Compose Multiplatform has no built-in equivalent.
                val chosen = chooseFile()
                if (chosen != null) {
                    scope.launch {
                        busy = true
                        account.signInFromFile(chosen)
                        busy = false
                    }
                }
            },
        ) { Text("Choose file…") }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = { pasting = !pasting }) {
            Text(if (pasting) "Hide" else "Paste a cookie instead")
        }
        if (pasting) {
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("Cookie header or cookies.txt contents") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                enabled = !busy && pasted.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val result = account.signInFromPastedText(pasted)
                        // Cleared only on success. Leaving it after a failure lets the user correct
                        // a typo rather than fetch the whole thing again.
                        if (result is AccountState.SignedIn) pasted = ""
                        busy = false
                    }
                },
            ) { Text("Sign in") }
        }
    }
}

/**
 * The OS file picker.
 *
 * `FileDialog` rather than `JFileChooser`: it is the native dialog on Windows and macOS, so it looks
 * like every other open dialog on the machine, and it is what someone will be looking for after
 * saving a file from their browser.
 */
private fun chooseFile(): File? {
    val dialog = FileDialog(null as Frame?, "Choose cookies.txt", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
