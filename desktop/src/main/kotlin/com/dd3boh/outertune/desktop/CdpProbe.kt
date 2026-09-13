/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The smallest thing [CdpSession] must be able to do, step by step.
 *
 * Written because the token minter hung with no output at all, which says nothing about *where* it
 * hung - launching, attaching, or evaluating. Each step here prints before and after, so the last
 * line printed names the step that did not finish.
 *
 * `gradlew :desktop:cdpProbe`
 */
object CdpProbe {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("1. opening a page on youtube.com…")
        val session = withTimeoutOrNull(60_000) { CdpSession.open("https://www.youtube.com/") }
        if (session == null) {
            println("   FAILED: no session (no browser, or it never attached)")
            return@runBlocking
        }
        println("   ok")

        try {
            println("2. evaluating 1+1…")
            val sum = withTimeoutOrNull(20_000) { session.evaluateString("1+1") }
            println("   ${sum ?: "TIMED OUT"}")

            println("3. reading document.title…")
            val title = withTimeoutOrNull(20_000) { session.evaluateString("document.title") }
            println("   ${title ?: "TIMED OUT"}")

            println("4. awaiting a resolved promise…")
            val promised = withTimeoutOrNull(20_000) {
                session.evaluateString("Promise.resolve('promises work')")
            }
            println("   ${promised ?: "TIMED OUT"}")

            println("5. loading the botguard script…")
            val html = CdpProbe::class.java.getResourceAsStream("/po_token.html")
                ?.bufferedReader()?.use { it.readText() }
            if (html == null) {
                println("   FAILED: po_token.html not on the classpath")
                return@runBlocking
            }
            val script = PoTokenMinter().extractScript(html)
            println("   script is ${script.length} characters")
            val loaded = withTimeoutOrNull(30_000) {
                session.evaluateString("$script; typeof runBotGuard")
            }
            println("   typeof runBotGuard = ${loaded ?: "TIMED OUT"}")
        } finally {
            session.close()
        }
    }
}
