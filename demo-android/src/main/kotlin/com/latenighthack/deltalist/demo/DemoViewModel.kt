package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.mutableDeltaFlowOf
import com.latenighthack.deltalist.operators.lazyMapWithAccess
import com.latenighthack.deltalist.operators.withStableLazyIds
import com.latenighthack.deltalist.paginatedDeltaFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DemoViewModel {
    private val _items = mutableDeltaFlowOf<Item>()
    val items: DeltaFlow<Item> = _items

    // Scope for ticking items - uses SupervisorJob so individual item cancellation doesn't affect others
    private val tickingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Paginated list demo - 10,000 numbers with simulated fetch delay
    private val _paginatedLoadingDirection = MutableStateFlow<LoadDirection?>(null)
    val paginatedLoadingDirection: StateFlow<LoadDirection?> = _paginatedLoadingDirection.asStateFlow()

    private val _paginatedLoadedCount = MutableStateFlow(0)
    val paginatedLoadedCount: StateFlow<Int> = _paginatedLoadedCount.asStateFlow()

    val paginatedNumbers: DeltaFlow<Int> = paginatedDeltaFlow(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        fetchWindowSize = 5,
        startToken = 0
    ) { direction, pageToken ->
        // Signal loading started
        _paginatedLoadingDirection.value = direction

        try {
            // Simulate network delay
            delay(500)

            val pageSize = 50
            val totalItems = 10_000
            val startIndex = pageToken * pageSize
            val endIndex = minOf(startIndex + pageSize, totalItems)

            val items = (startIndex until endIndex).toList()

            // Update loaded count
            _paginatedLoadedCount.value = endIndex

            Page(
                items = items,
                beforeToken = if (pageToken > 0) pageToken - 1 else null,
                afterToken = if (endIndex < totalItems) pageToken + 1 else null,
                estimatedTotalSize = totalItems
            )
        } finally {
            // Signal loading completed
            _paginatedLoadingDirection.value = null
        }
    }

    /**
     * Lazy-mapped flow with stable IDs for platform binding.
     *
     * The stable ID follows each item through mutations (insert, remove, move).
     * Platform bindings should use stableId as the key for UI framework item tracking.
     */
    val tickingItems: DeltaFlow<StableLazyAccess<TickingItem>> = _items
        .lazyMapWithAccess { item -> TickingItem(item, tickingScope) }
        .withStableLazyIds()

    private var counter = 0

    fun addItem() {
        val id = UUID.randomUUID().toString()
        _items.append(Item(id, "Item ${++counter}"))
    }

    fun removeItem(index: Int) {
        if (index in 0 until _items.value.size) {
            _items.removeAt(index)
        }
    }

    fun insertBefore(index: Int) {
        val id = UUID.randomUUID().toString()
        _items.insert(index, Item(id, "Inserted ${++counter}"))
    }

    fun insertAfter(index: Int) {
        val id = UUID.randomUUID().toString()
        _items.insert(index + 1, Item(id, "Inserted ${++counter}"))
    }

    fun batchAdd() {
        _items.update { list ->
            repeat(5) {
                list.add(Item(UUID.randomUUID().toString(), "Batch ${++counter}"))
            }
        }
    }

    fun clear() {
        _items.clear()
    }
}
