/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.dd3boh.outertune.MainActivity

/**
 * What the launcher opens from the widget's "Edit" / "Customise" option (long-press the widget):
 * the widget editor in the app's settings. No screen of its own - it accepts straight away, so the
 * launcher keeps the widget, and hands over to the app.
 *
 * On Android 13+ the widget declares this optional, so placing a widget does not open anything;
 * older versions open it once on placement too, which lands on the same editor.
 */
class WidgetConfigureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_WIDGET_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
