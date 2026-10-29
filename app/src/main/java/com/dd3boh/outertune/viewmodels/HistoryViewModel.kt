package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.HistorySource
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.HistoryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    var historySource = MutableStateFlow(HistorySource.LOCAL)

    private val today = LocalDate.now()
    private val thisMonday = today.with(DayOfWeek.MONDAY)
    private val lastMonday = thisMonday.minusDays(7)
    val historyPage = mutableStateOf<HistoryPage?>(null)
    val historyError = MutableStateFlow<String?>(null)
    private val isLoadingMore = MutableStateFlow(false)

    val events = database.events()
        .map { events ->
            events.groupBy {
                val date = it.event.timestamp.toLocalDate()
                val daysAgo = ChronoUnit.DAYS.between(date, today).toInt()
                when {
                    daysAgo == 0 -> DateAgo.Today
                    daysAgo == 1 -> DateAgo.Yesterday
                    date >= thisMonday -> DateAgo.ThisWeek
                    date >= lastMonday -> DateAgo.LastWeek
                    else -> DateAgo.Other(date.withDayOfMonth(1))
                }
            }.toSortedMap(compareBy { dateAgo ->
                when (dateAgo) {
                    DateAgo.Today -> 0L
                    DateAgo.Yesterday -> 1L
                    DateAgo.ThisWeek -> 2L
                    DateAgo.LastWeek -> 3L
                    is DateAgo.Other -> ChronoUnit.DAYS.between(dateAgo.date, today)
                }
            })
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    init {
        fetchRemoteHistory()
    }
    
    fun fetchRemoteHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            YouTube.musicHistory().onSuccess {
                historyPage.value = it
            }.onFailure {
                reportException(it)
                historyError.value = it.message ?: it.javaClass.simpleName
            }
        }
    }

    fun loadMoreRemoteHistory() {
        val continuation = historyPage.value?.continuation ?: return
        if (isLoadingMore.value) return
        isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            YouTube.musicHistoryContinuation(continuation).onSuccess { page ->
                val current = historyPage.value
                val mergedSections = appendHistorySections(current?.sections.orEmpty(), page.sections.orEmpty())
                historyPage.value = HistoryPage(sections = mergedSections, continuation = page.continuation)
            }.onFailure {
                reportException(it)
                historyError.value = it.message ?: it.javaClass.simpleName
            }
            isLoadingMore.value = false
        }
    }
}

/**
 * [current] with a further page of history, [next], added to the end.
 *
 * Section by section. The whole page used to be poured into the last date already shown, so when a
 * page reached back past it, plays from earlier days were listed under that day. A section with no
 * title, or with the same title as the last one, is the last one continuing across the page break
 * and is appended to it; any other title starts a section of its own.
 */
internal fun appendHistorySections(
    current: List<HistoryPage.HistorySection>,
    next: List<HistoryPage.HistorySection>,
): List<HistoryPage.HistorySection> {
    val merged = current.toMutableList()
    for (section in next) {
        if (section.songs.isEmpty()) continue
        val last = merged.lastOrNull()
        if (last != null && (section.title.isBlank() || section.title == last.title)) {
            merged[merged.lastIndex] = last.copy(songs = last.songs + section.songs)
        } else {
            merged += section
        }
    }
    return merged
}

sealed class DateAgo {
    object Today : DateAgo()
    object Yesterday : DateAgo()
    object ThisWeek : DateAgo()
    object LastWeek : DateAgo()
    class Other(val date: LocalDate) : DateAgo() {
        override fun equals(other: Any?): Boolean {
            if (other is Other) return date == other.date
            return super.equals(other)
        }

        override fun hashCode(): Int = date.hashCode()
    }
}
