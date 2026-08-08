/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R

/**
 * The launcher icons this app ships, and the machinery for switching between them.
 *
 * Android has no API for setting an app's icon: the icon is a manifest attribute, read by the
 * launcher, and an installed package cannot rewrite its own manifest. What it *can* do is enable
 * and disable components, so every icon is declared as an `<activity-alias>` pointing at
 * MainActivity, and exactly one of them is enabled at a time.
 *
 * [previewBackground] and [previewForeground] exist because a settings screen cannot simply draw
 * `@mipmap/ic_launcher`: that resource is an `<adaptive-icon>`, which is a launcher-side
 * composition rather than a drawable Compose can render. The picker rebuilds the same two layers
 * itself instead.
 */
enum class AppIcon(
    /** Matches the `android:name` of the alias in AndroidManifest.xml. */
    val aliasSuffix: String,
    @StringRes val titleId: Int,
    @ColorRes val previewBackground: Int,
    @DrawableRes val previewForeground: Int,
) {
    DEFAULT(
        aliasSuffix = ".IconDefault",
        titleId = R.string.app_icon_default,
        previewBackground = R.color.ic_launcher_background,
        previewForeground = R.drawable.launcher_foreground,
    ),
    MIDNIGHT(
        aliasSuffix = ".IconMidnight",
        titleId = R.string.app_icon_midnight,
        previewBackground = R.color.ic_launcher_background_midnight,
        previewForeground = R.drawable.launcher_foreground,
    ),
    MONO_LIGHT(
        aliasSuffix = ".IconMonoLight",
        titleId = R.string.app_icon_mono_light,
        previewBackground = R.color.ic_launcher_background_mono_light,
        previewForeground = R.drawable.launcher_mono_dark,
    ),
    MONO_DARK(
        aliasSuffix = ".IconMonoDark",
        titleId = R.string.app_icon_mono_dark,
        previewBackground = R.color.ic_launcher_background_mono_dark,
        previewForeground = R.drawable.launcher_mono_light,
    ),
    CRIMSON(
        aliasSuffix = ".IconCrimson",
        titleId = R.string.app_icon_crimson,
        previewBackground = R.color.ic_launcher_background_crimson,
        previewForeground = R.drawable.launcher_mono_light,
    ),
    OCEAN(
        aliasSuffix = ".IconOcean",
        titleId = R.string.app_icon_ocean,
        previewBackground = R.color.ic_launcher_background_ocean,
        previewForeground = R.drawable.launcher_mono_light,
    );

    fun component(context: Context): ComponentName = ComponentName(context, ALIAS_CLASS_PREFIX + aliasSuffix)

    companion object {
        /**
         * Aliases are named relative to the manifest package, which is the *namespace* - not the
         * applicationId. Debug builds carry applicationIdSuffix ".debug", so building the class
         * name from context.packageName would look for com.dd3boh.outertune.debug.IconDefault,
         * a component that does not exist, and every icon switch would silently do nothing.
         * Taking it from MainActivity gets the namespace whatever the variant.
         */
        private val ALIAS_CLASS_PREFIX: String =
            MainActivity::class.java.name.substringBeforeLast('.')

        /**
         * Switches the launcher icon to [target].
         *
         * The new alias is enabled before the old ones are disabled. Doing it the other way round
         * leaves a moment with no enabled launcher component at all, and some launchers notice and
         * drop the app from the home screen and app drawer permanently - the user has to reinstall
         * to get it back. Overlapping instead means a brief moment with two entries, which
         * launchers handle by refreshing.
         *
         * DONT_KILL_APP keeps playback alive across the change. It also means launchers can take a
         * few seconds - or a home-screen refresh - to notice, which is normal and not worth
         * pretending otherwise in the UI.
         */
        fun apply(context: Context, target: AppIcon) {
            val pm = context.packageManager
            val enabled = target.component(context)

            if (pm.getComponentEnabledSetting(enabled) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    enabled,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }

            entries.filter { it != target }.forEach { other ->
                val component = other.component(context)
                if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }

        /**
         * Which icon the package manager currently has enabled.
         *
         * Read from the system rather than from a stored preference: the two can disagree after a
         * restore from backup, where the preference comes back but component state does not.
         */
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: DEFAULT
        }
    }
}
