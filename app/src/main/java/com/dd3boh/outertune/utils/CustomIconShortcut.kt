/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.dd3boh.outertune.MainActivity

/**
 * Puts a launcher tile carrying an image of the user's choosing on the home screen.
 *
 * This exists because the thing people usually mean by "use my own icon" - replacing the app's
 * launcher icon with an arbitrary picture - is not something Android permits. An app icon is a
 * resource compiled into the APK and resolved by the launcher, so the only icons an installed
 * build can offer are ones it already contains (see [AppIcon]). A pinned shortcut is the one
 * place the system does accept a runtime bitmap, so that is what this uses.
 *
 * The result behaves like the app: same task, same launch behaviour. What it is not is a
 * replacement - the original icon stays in the app drawer, and the tile disappears if the app is
 * uninstalled. Worth being straight about that in the UI rather than letting it surprise anyone.
 */
object CustomIconShortcut {

    /** Adaptive icons are masked heavily by the launcher, so oversample and let it crop. */
    private const val ICON_SIZE_PX = 512

    fun isSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * @return null on success, or a short reason the shortcut could not be created.
     */
    fun create(context: Context, imageUri: Uri, label: String): String? {
        if (!isSupported(context)) {
            return "This launcher does not support pinned shortcuts."
        }
        val bitmap = decodeSquare(context, imageUri)
            ?: return "That image could not be read."

        val shortcut = ShortcutInfoCompat.Builder(context, "custom_icon_" + label.hashCode())
            .setShortLabel(label.ifBlank { "OuterTune" })
            .setLongLabel(label.ifBlank { "OuterTune" })
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            // A pinned shortcut's intent must name an action explicitly; without one the system
            // rejects it with an exception rather than a return value.
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            )
            .build()

        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
            .fold(
                onSuccess = { accepted ->
                    if (accepted) null else "The launcher declined to add the shortcut."
                },
                onFailure = { it.message ?: "The shortcut could not be created." },
            )
    }

    /**
     * Loads [uri] as a square bitmap, downsampling on the way in.
     *
     * Decoding at full size first would mean holding a phone-camera JPEG in memory - tens of
     * megabytes - purely to shrink it to 512px, which is a real out-of-memory risk on the
     * devices least able to absorb it.
     */
    private fun decodeSquare(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_SIZE_PX && bounds.outHeight / (sample * 2) >= ICON_SIZE_PX) {
            sample *= 2
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        // Centre-crop to a square before scaling, so the picture keeps its proportions instead of
        // being stretched into one.
        val side = minOf(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, ICON_SIZE_PX, ICON_SIZE_PX, true)
        if (cropped != decoded) decoded.recycle()
        if (scaled != cropped) cropped.recycle()
        scaled
    }.getOrNull()
}
