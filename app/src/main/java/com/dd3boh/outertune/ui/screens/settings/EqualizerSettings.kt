/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EqContrastColorKey
import com.dd3boh.outertune.constants.EqUseDialsKey
import com.dd3boh.outertune.constants.EqValueColorGradientKey
import com.dd3boh.outertune.constants.EqualizerProfilesKey
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.models.EqualizerProfile
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

/**
 * A persistent Settings home for the equalizer options that otherwise only live inside the
 * in-player panel (EqualizerPanel.kt) - same preference keys either way, so a change here or
 * there stays in sync. Profile management is duplicated here rather than shared with the panel
 * because the panel's version pushes changes live to a bound player service, while this screen may
 * be opened with no active playback session at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val playerConnection = LocalPlayerConnection.current

    var useDials by rememberPreference(EqUseDialsKey, defaultValue = true)
    var useContrastColor by rememberPreference(EqContrastColorKey, defaultValue = true)
    var useValueGradient by rememberPreference(EqValueColorGradientKey, defaultValue = false)

    var settingsJson by rememberPreference(EqualizerSettingsKey, "")
    val settings = remember(settingsJson) {
        EqualizerSettings.parse(settingsJson).getOrDefault(EqualizerSettings.DEFAULT)
    }

    var profilesJson by rememberPreference(EqualizerProfilesKey, "")
    val savedProfiles = remember(profilesJson) { EqualizerProfile.listFromJson(profilesJson) }
    val savedProfileNames = remember(savedProfiles) { savedProfiles.map { it.name }.toSet() }
    val builtInProfileNames = remember { EqualizerSettings.PRESETS.keys.toList() }
    val profileNames = remember(savedProfiles, builtInProfileNames) {
        builtInProfileNames + savedProfiles.map { it.name }.filter { it !in builtInProfileNames }
    }

    fun loadProfile(name: String) {
        val toApply = savedProfiles.find { it.name == name }?.settings
            ?: EqualizerProfile.factoryDefault(name)?.settings
            ?: return
        val newSettings = toApply.copy(enabled = settings.enabled)
        settingsJson = newSettings.toJson()
        playerConnection?.service?.equalizerAudioProcessor?.setSettings(newSettings)
    }

    fun saveProfile(name: String) {
        val newList = savedProfiles.filterNot { it.name == name } + EqualizerProfile(name, settings)
        profilesJson = EqualizerProfile.listToJson(newList)
    }

    fun deleteProfile(name: String) {
        profilesJson = EqualizerProfile.listToJson(savedProfiles.filterNot { it.name == name })
    }

    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showAutoEqDialog by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.eq_settings_appearance))

        SwitchPreference(
            title = { Text(stringResource(R.string.eq_settings_use_dials_title)) },
            description = stringResource(R.string.eq_settings_use_dials_description),
            icon = { Icon(Icons.Rounded.Tune, null) },
            checked = useDials,
            onCheckedChange = { useDials = it }
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.eq_settings_contrast_color_title)) },
            description = stringResource(R.string.eq_settings_contrast_color_description),
            icon = { Icon(Icons.Rounded.Palette, null) },
            checked = useContrastColor,
            onCheckedChange = { useContrastColor = it }
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.eq_settings_value_gradient_title)) },
            description = stringResource(R.string.eq_settings_value_gradient_description),
            icon = { Icon(Icons.Rounded.Palette, null) },
            checked = useValueGradient,
            onCheckedChange = { useValueGradient = it }
        )

        PreferenceGroupTitle(title = stringResource(R.string.eq_settings_profiles))

        profileNames.forEach { name ->
            PreferenceEntry(
                title = { Text(name) },
                icon = { Icon(Icons.Rounded.Equalizer, null) },
                onClick = { loadProfile(name) },
                trailingContent = if (name in savedProfileNames) {
                    {
                        IconButton(onClick = { deleteProfile(name) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                        }
                    }
                } else null
            )
        }
        PreferenceEntry(
            title = { Text(stringResource(R.string.eq_settings_save_as_profile)) },
            icon = { Icon(Icons.Rounded.Add, null) },
            onClick = { showSaveAsDialog = true }
        )

        PreferenceGroupTitle(title = "AutoEQ")

        PreferenceEntry(
            title = { Text(stringResource(R.string.eq_settings_autoeq_title)) },
            description = stringResource(R.string.eq_settings_autoeq_description),
            icon = { Icon(Icons.Rounded.Search, null) },
            onClick = { showAutoEqDialog = true }
        )
    }

    if (showSaveAsDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.eq_settings_save_as_profile)) },
            placeholder = { Text(stringResource(R.string.eq_settings_profile_name)) },
            isInputValid = { it.isNotBlank() },
            onDone = { name -> saveProfile(name.trim()) },
            onDismiss = { showSaveAsDialog = false },
        )
    }

    if (showAutoEqDialog) {
        AutoEqSearchDialog(onDismiss = { showAutoEqDialog = false })
    }

    TopAppBar(
        title = { Text(stringResource(R.string.eq_settings_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

/**
 * Framework only - AutoEQ (https://github.com/jaakkopasanen/AutoEq) publishes measured correction
 * curves for thousands of headphone models, keyed by model name. Wiring an actual result up to
 * [EqualizerSettings] means picking a data source (a bundled snapshot vs. fetching AutoEq's
 * results database live) and mapping its parametric-EQ format onto this app's band model - left as
 * a later step; this just gives the feature a real entry point and search UI to build that behind.
 */
@Composable
private fun AutoEqSearchDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(Icons.Rounded.Search, null) },
        title = { Text(stringResource(R.string.eq_settings_autoeq_title)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.eq_settings_autoeq_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Text(
            text = stringResource(R.string.eq_settings_autoeq_coming_soon),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
