package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live probe, skipped unless OT_PROBE is set: what each home chip actually returns. */
class ChipProbe {
    @Test
    fun chips() = runBlocking {
        assumeTrue(System.getenv("OT_PROBE") != null)
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = YouTube.visitorData().getOrThrow()
        val home = YouTube.home().getOrThrow()
        println("PROBE home sections=${home.sections.size} chips=${home.chips?.map { it.title }}")
        home.chips.orEmpty().forEach { chip ->
            val page = YouTube.home(params = chip.endpoint?.params)
            println("PROBE chip '${chip.title}' params=${chip.endpoint?.params?.take(12)} -> " +
                page.fold({ "sections=${it.sections.size} ${it.sections.take(3).map { s -> s.title }}" }, { "FAIL ${it::class.simpleName}: ${it.message?.take(160)}" }))
        }
    }
}
