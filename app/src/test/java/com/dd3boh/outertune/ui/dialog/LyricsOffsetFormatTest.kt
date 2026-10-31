package com.dd3boh.outertune.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-song lyrics offset is shown signed, the same way as on the desktop. */
class LyricsOffsetFormatTest {

    @Test
    fun laterIsPlus() = assertEquals("+250 ms", formatLyricsOffset(250))

    @Test
    fun earlierIsARealMinus() = assertEquals("−100 ms", formatLyricsOffset(-100))

    @Test
    fun noneIsUnsigned() = assertEquals("0 ms", formatLyricsOffset(0))
}
