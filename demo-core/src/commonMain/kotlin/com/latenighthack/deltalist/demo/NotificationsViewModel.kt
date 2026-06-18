package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.mutableDeltaListOf
import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A fake download whose progress is its own observable state, independent of list deltas. */
class DownloadVm(val id: String, val fileName: String) {
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    fun advance() {
        _progress.value = (_progress.value + 10).coerceAtMost(100)
    }
}

class NotificationsViewModel {
    private val _downloads = mutableDeltaListOf<DownloadVm>()

    /** Raw list for the on-screen mirror. */
    val downloads: DeltaList<DownloadVm> = _downloads

    /** Stable-id projection consumed by the notifier. */
    val stableDownloads: DeltaList<StableItem<DownloadVm>> = _downloads.withStableIds()

    private var counter = 0

    fun add() {
        val n = ++counter
        _downloads.append(DownloadVm(randomUUID(), "archive_$n.zip"))
    }

    fun removeById(id: String) {
        val index = _downloads.value.indexOfFirst { it.id == id }
        if (index >= 0) _downloads.removeAt(index)
    }

    fun tickAll() {
        _downloads.value.forEach { it.advance() }
    }

    fun clear() {
        _downloads.clear()
    }
}
