/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import com.dd3boh.outertune.BuildConfig

/**
 * Timber-shaped logging over android.util.Log.
 *
 * The recognition code was written against Timber, which takes a format string and arguments.
 * android.util.Log does not - it takes a message and optionally a throwable - so calls like
 * `Log.d(TAG, "%d bytes", size)` do not compile at all. Rather than rewrite every call site into
 * string templates, this keeps the original shape and does the formatting here.
 *
 * Formatting only happens when the message will actually be printed, so the arguments cost
 * nothing on a release build.
 */
internal object RecogLog {
    private val verbose = BuildConfig.DEBUG

    fun d(tag: String, message: String, vararg args: Any?) {
        if (verbose) Log.d(tag, format(message, args))
    }

    fun i(tag: String, message: String, vararg args: Any?) {
        Log.i(tag, format(message, args))
    }

    fun w(tag: String, message: String, vararg args: Any?) {
        Log.w(tag, format(message, args))
    }

    fun w(tag: String, t: Throwable, message: String, vararg args: Any?) {
        Log.w(tag, format(message, args), t)
    }

    fun e(tag: String, message: String, vararg args: Any?) {
        Log.e(tag, format(message, args))
    }

    fun e(tag: String, t: Throwable, message: String, vararg args: Any?) {
        Log.e(tag, format(message, args), t)
    }

    /**
     * A malformed format string must not take down recognition, so a failure here falls back to
     * the raw message rather than throwing.
     */
    private fun format(message: String, args: Array<out Any?>): String =
        if (args.isEmpty()) message
        else runCatching { String.format(message, *args) }.getOrDefault(message)
}
