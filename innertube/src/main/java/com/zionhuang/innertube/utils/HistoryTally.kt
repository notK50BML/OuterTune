package com.zionhuang.innertube.utils

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HistoryPage
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Plays read out of the account's YouTube Music history, for Stats - shared by the phone and the
 * desktop so the two count the same way.
 *
 * History has no timestamps, only sections: "Today", "Yesterday", "This week", "Last week", then
 * months. A section is dated by the oldest a play in it could be, and only sections that fit inside
 * the period being shown are counted - the history used to be added whole to every period, so a
 * week's chart gained plays from months ago.
 */
object HistoryTally {

    /** One song's plays in the history, with the metadata to show it if it is not known locally. */
    data class Plays(val song: SongItem, val plays: Int)

    /**
     * The oldest, in days counting today as 1, that a play in the section called [title] can be -
     * or null for a title that cannot be dated, which is then counted only for all time.
     * English titles: the clients ask YouTube for English.
     */
    fun maxAgeDays(title: String, today: LocalDate = LocalDate.now()): Long? {
        when (title.trim().lowercase()) {
            "today" -> return 1
            "yesterday" -> return 2
            "this week" -> return today.dayOfWeek.value.toLong().coerceAtLeast(2)
            "last week" -> return today.dayOfWeek.value + 7L
            "this month" -> return today.dayOfMonth.toLong().coerceAtLeast(2)
            "last month" -> return ChronoUnit.DAYS.between(today.minusMonths(1).withDayOfMonth(1), today) + 1
        }
        // "March 2026", or "March" for this year.
        val parts = title.trim().split(Regex("\\s+"))
        val month = Month.entries.firstOrNull {
            it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equals(parts.firstOrNull(), ignoreCase = true)
        } ?: return null
        val year = when {
            parts.size >= 2 -> parts[1].toIntOrNull() ?: return null
            // A month name alone is this year's - or last year's, if that month has not come yet.
            month.value > today.monthValue -> today.year - 1
            else -> today.year
        }
        val start = LocalDate.of(year, month, 1)
        if (start.isAfter(today)) return null
        return ChronoUnit.DAYS.between(start, today) + 1
    }

    /**
     * Each song's plays in [sections], counting only sections no older than [periodDays] - all of
     * them when it is null. YouTube writes a history entry once a song has been played through, so
     * each entry is one completed play.
     */
    fun tally(
        sections: List<HistoryPage.HistorySection>,
        periodDays: Long?,
        today: LocalDate = LocalDate.now(),
    ): Map<String, Plays> {
        val counts = LinkedHashMap<String, Plays>()
        for (section in sections) {
            if (periodDays != null) {
                val age = maxAgeDays(section.title, today) ?: continue
                if (age > periodDays) continue
            }
            for (song in section.songs) {
                val seen = counts[song.id]
                counts[song.id] = seen?.copy(plays = seen.plays + 1) ?: Plays(song, 1)
            }
        }
        return counts
    }

    /**
     * How many plays the history adds to [localPlays] for one song: none unless it has more.
     *
     * The larger of the two rather than their sum, because the history already holds every play
     * shared with YouTube Music - this device's included when it shares its plays. Adding the two
     * counted those plays twice. Taking the larger can only undercount, by plays made both here and
     * elsewhere that the history did not keep, which is the honest direction to be wrong in.
     */
    fun extraPlays(localPlays: Int, historyPlays: Int): Int = (historyPlays - localPlays).coerceAtLeast(0)
}
