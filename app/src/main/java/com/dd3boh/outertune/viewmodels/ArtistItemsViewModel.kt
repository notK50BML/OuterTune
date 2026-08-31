package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.BrowseEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    val title = MutableStateFlow("")
    val itemsPage = MutableStateFlow<ItemsPage?>(null)

    init {
        viewModelScope.launch {
            YouTube.artistItems(
                BrowseEndpoint(
                    browseId = browseId,
                    params = params
                )
            ).onSuccess { artistItemsPage ->
                title.value = artistItemsPage.title
                itemsPage.value = ItemsPage(
                    items = artistItemsPage.items.distinctBy { it.id },
                    continuation = artistItemsPage.continuation
                )
            }.onFailure {
                reportException(it)
            }
        }
    }

    /**
     * Guards against re-requesting a page that is already on its way. The screen drives this from a
     * snapshotFlow on the scroll position, and it has two of them - one for the list layout and one
     * for the grid - so a single scroll to the end could call in twice over. Worse, the flow keeps
     * emitting while the request is in flight, and the continuation token only changes once the
     * response lands, so every one of those emissions asked for the exact same page again. The
     * duplicates were invisible (distinctBy swallowed them) but they were real requests competing
     * with the one that mattered, on a screen already waiting on the network.
     */
    private var loadingMore = false

    fun loadMore() {
        if (loadingMore) return
        val oldItemsPage = itemsPage.value ?: return
        val continuation = oldItemsPage.continuation ?: return
        loadingMore = true
        viewModelScope.launch {
            try {
                YouTube.artistItemsContinuation(continuation)
                    .onSuccess { artistItemsContinuationPage ->
                        itemsPage.update {
                            ItemsPage(
                                items = (oldItemsPage.items + artistItemsContinuationPage.items).distinctBy { it.id },
                                continuation = artistItemsContinuationPage.continuation
                            )
                        }
                    }.onFailure {
                        reportException(it)
                    }
            } finally {
                loadingMore = false
            }
        }
    }
}
