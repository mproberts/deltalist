package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.mutableDeltaFlowOf
import java.util.UUID

class DemoViewModel {
    private val _items = mutableDeltaFlowOf<Item>()
    val items: DeltaFlow<Item> = _items

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
