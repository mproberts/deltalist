package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.mutableDeltaFlowOf
import com.latenighthack.deltalist.operators.lazyMapWithAccess
import com.latenighthack.deltalist.operators.withStableLazyIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

class DemoViewModel {
    private val _items = mutableDeltaFlowOf<Item>()
    val items: DeltaFlow<Item> = _items

    // Scope for ticking items - uses SupervisorJob so individual item cancellation doesn't affect others
    private val tickingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
