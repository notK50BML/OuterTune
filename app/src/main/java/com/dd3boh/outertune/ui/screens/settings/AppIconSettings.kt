/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.AppIcon
import com.dd3boh.outertune.utils.CustomIconShortcut

/** Size of a swatch as the launcher would show it, and of the layer it is cropped from. */
private val SWATCH_SIZE = 64.dp

/**
 * An adaptive icon's layers are 108dp and the launcher's mask reveals the middle 72dp, so a
 * faithful preview draws the foreground at 108/72 of the tile and clips the overflow.
 */
private val SWATCH_LAYER_SIZE = SWATCH_SIZE * 108 / 72

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current

    // Read from the package manager rather than a stored preference, so this always shows what
    // the launcher is actually using - the two drift apart after a restore from backup.
    var selected by remember { mutableStateOf(AppIcon.current(context)) }
    var pickedImage by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResultCompat { uri ->
        if (uri != null) pickedImage = uri
    }

    pickedImage?.let { uri ->
        TextFieldDialog(
            icon = { Icon(Icons.Rounded.AddPhotoAlternate, null) },
            title = { Text(stringResource(R.string.app_icon_custom_name)) },
            initialTextFieldValue = TextFieldValue(stringResource(R.string.app_name)),
            isInputValid = { it.isNotBlank() },
            onDone = { name ->
                status = CustomIconShortcut.create(context, uri, name.trim())
                    ?: context.getString(R.string.app_icon_custom_done)
            },
            onDismiss = { pickedImage = null },
        )
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.app_icon))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Laid out by hand in rows of three rather than with a grid, because this sits
                // inside a scrolling column and a lazy grid inside one has no height to measure.
                AppIcon.entries.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { icon ->
                            IconSwatch(
                                icon = icon,
                                isSelected = icon == selected,
                                onClick = {
                                    AppIcon.apply(context, icon)
                                    selected = icon
                                    status = context.getString(R.string.app_icon_changed)
                                },
                            )
                        }
                        // Keep a short final row aligned with the ones above it.
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.size(SWATCH_SIZE))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Text(
            text = stringResource(R.string.app_icon_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        PreferenceGroupTitle(title = stringResource(R.string.app_icon_custom))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.app_icon_custom_title)) },
                description = status ?: stringResource(R.string.app_icon_custom_description),
                icon = { Icon(Icons.Rounded.AddPhotoAlternate, null) },
                isEnabled = CustomIconShortcut.isSupported(context),
                onClick = { pickImage("image/*") },
            )
        }

        Text(
            text = stringResource(R.string.app_icon_custom_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.app_icon)) },
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

@Composable
private fun IconSwatch(
    icon: AppIcon,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE)
                .clip(RoundedCornerShape(SWATCH_SIZE / 4))
                .background(colorResource(icon.previewBackground))
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(SWATCH_SIZE / 4),
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon.previewForeground),
                contentDescription = null,
                modifier = Modifier.size(SWATCH_LAYER_SIZE),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(icon.titleId),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Wrapper purely so the call site above reads as one expression. GetContent is used rather than
 * the photo picker because it works the same way back to API 24, which this app still supports.
 */
@Composable
private fun rememberLauncherForActivityResultCompat(
    onResult: (Uri?) -> Unit,
): (String) -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { onResult(it) }
    return { mime -> launcher.launch(mime) }
}
