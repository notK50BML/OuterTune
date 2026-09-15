package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * What a download state means for `song.dateDownload`.
 *
 * Worth pinning because the column has exactly one value meaning "not downloaded" - NULL - and every
 * query is written against it. A state that maps to anything else is counted as a download by
 * `dateDownload IS NOT NULL`, and the enqueue guard then refuses to queue the song again. That is
 * how a single failed download made a song permanently un-downloadable, with tapping Download doing
 * nothing at all.
 *
 * The state is taken as an Int on purpose: building a real Download needs a DownloadRequest, which
 * needs android.net.Uri, which is not available in a plain unit test.
 */
class DownloadStateTest {

    private val updateTime = 1_700_000_000_000L

    @Test
    fun `a completed download records when it finished`() {
        val date = downloadDateFor(Download.STATE_COMPLETED, updateTime)
        assertEquals(
            Instant.ofEpochMilli(updateTime).atZone(ZoneOffset.UTC).toLocalDateTime(),
            date,
        )
    }

    @Test
    fun `a download in flight is marked as in flight`() {
        listOf(
            Download.STATE_DOWNLOADING,
            Download.STATE_QUEUED,
            Download.STATE_RESTARTING,
        ).forEach { state ->
            assertEquals(
                "state $state should count as in flight",
                DownloadUtil.STATE_DOWNLOADING,
                downloadDateFor(state, updateTime),
            )
        }
    }

    @Test
    fun `a failed download is not a download`() {
        // The bug this test exists for. Recorded as a sentinel, this came back from
        // "dateDownload IS NOT NULL" as though the song were downloaded, and the enqueue guard then
        // refused to queue it - forever, silently, however many times Download was tapped.
        assertNull(downloadDateFor(Download.STATE_FAILED, updateTime))
    }

    @Test
    fun `a stopped or removing download is not a download either`() {
        assertNull(downloadDateFor(Download.STATE_STOPPED, updateTime))
        assertNull(downloadDateFor(Download.STATE_REMOVING, updateTime))
    }

    @Test
    fun `only completed and in-flight states are ever recorded`() {
        // Stated as a property over every state Media3 defines, so a new one added upstream defaults
        // to "not downloaded" rather than to something that blocks the song.
        val recorded = listOf(
            Download.STATE_COMPLETED,
            Download.STATE_DOWNLOADING,
            Download.STATE_QUEUED,
            Download.STATE_RESTARTING,
        )
        (0..10).forEach { state ->
            val date = downloadDateFor(state, updateTime)
            if (state in recorded) {
                assertNotNull("state $state should be recorded", date)
            } else {
                assertNull("state $state should not count as a download", date)
            }
        }
    }

    @Test
    fun `the in-flight marker is distinguishable from the invalid one`() {
        // They differ by a single millisecond at the epoch, which is exactly why confusing them was
        // possible: both are real LocalDateTimes and neither is null.
        assert(DownloadUtil.STATE_DOWNLOADING != DownloadUtil.STATE_INVALID)
    }

    @Test
    fun `the invalid marker is never what a state maps to`() {
        // Nothing should produce it any more - it survives only to recognise rows an older build
        // already wrote, so they can be cleared rather than left blocking their songs.
        (0..10).forEach { state ->
            assert(downloadDateFor(state, updateTime) != DownloadUtil.STATE_INVALID) {
                "state $state still maps to the invalid sentinel"
            }
        }
    }
}
