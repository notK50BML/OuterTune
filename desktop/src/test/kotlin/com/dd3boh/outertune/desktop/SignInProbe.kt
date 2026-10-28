package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

// Temporary: the browser sign-in end to end, against a scratch library. Prints the outcome only,
// never the cookie. Not to be committed.
object SignInProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        runBlocking {
            YouTube.visitorData = YouTube.visitorData().getOrThrow()
            val library = LibraryStore(File(args[0]))
            val account = Account(library.database)
            val t0 = System.currentTimeMillis()
            val result = account.signInWithBrowser { println("PROGRESS ${(System.currentTimeMillis() - t0) / 1000}s $it") }
            println(
                "RESULT " + when (result) {
                    is AccountState.SignedIn -> "SignedIn as ${result.name}"
                    is AccountState.Expired -> "Expired: ${result.reason}"
                    AccountState.SignedOut -> "SignedOut"
                },
            )
            // Nothing kept: the scratch library forgets the session again.
            account.signOut()
        }
        exitProcess(0)
    }
}
