package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.StableItemImpl
import kotlinx.coroutines.flow.flow

/**
 * Wraps items with session-stable integer identifiers.
 *
 * The stable IDs follow items as they move through mutations (insert, remove, move).
 * On reload, all IDs are regenerated.
 *
 * When the source is backed by a [LazyList], the resulting list preserves lazy semantics:
 * - Items are auto-acquired on access
 * - Platform adapters detect [LazyList] and call release() automatically
 *
 * Use this at the boundary between deltalist-core and platform bindings to provide
 * stable keys without requiring the underlying data to implement Identifiable.
 *
 * Example:
 * ```
 * val items: DeltaList<MyItem> = ...
 * val stableItems: DeltaList<StableItem<MyItem>> = items.withStableIds()
 *
 * // In Compose:
 * items(stableItems, key = { it.stableId }) { stableItem ->
 *     MyItemCard(stableItem.value)
 * }
 * ```
 *
 * Example with lazy transformation:
 * ```
 * val items: DeltaList<Item> = ...
 * val lazyStableItems = items.lazyMap { transform(it) }.withStableIds()
 *
 * // Platform adapters handle lifecycle automatically
 * items(lazyStableItems, key = { it.stableId }) { stableItem ->
 *     MyCard(stableItem.value)  // auto-acquired, auto-released
 * }
 * ```
 */
fun <T> DeltaList<T>.withStableIds(): DeltaList<StableItem<T>> = flow {
    val adapter = StableIdAdapter<T>()

    collect { delta ->
        emit(adapter.applyDelta(delta))
    }
}

/**
 * Lazy list wrapper that adds stable IDs without accessing source items until needed.
 * Uses idMapping.size as the source of truth to ensure consistency between size and get().
 */
internal class StableItemList<T>(
    private val source: List<T>,
    private val idMapping: List<Int>
) : AbstractList<StableItem<T>>() {
    override val size: Int get() = idMapping.size

    override fun get(index: Int): StableItem<T> =
        StableItemImpl(idMapping[index], source[index])
}

/**
 * LazyList-aware wrapper that adds stable IDs while preserving lazy semantics.
 * Implements [LazyList] to allow platform adapters to manage item lifecycle.
 */
internal class StableItemLazyList<T>(
    private val source: LazyList<T>,
    private val idMapping: List<Int>
) : AbstractList<StableItem<T>>(), LazyList<StableItem<T>> {
    override val size: Int get() = idMapping.size

    override fun get(index: Int): StableItem<T> {
        // Accessing source[index] auto-acquires the item in the underlying LazyList
        return StableItemImpl(idMapping[index], source[index])
    }

    override fun release(index: Int) {
        source.release(index)
    }

    override fun releaseAll() {
        source.releaseAll()
    }

    override fun isAcquired(index: Int): Boolean {
        return source.isAcquired(index)
    }
}

/**
 * Maintains a mapping from list indices to stable IDs.
 *
 * The adapter assigns unique integer IDs to items as they enter the list,
 * and tracks their positions as mutations occur. IDs are session-unique
 * (unique within this adapter instance's lifetime).
 *
 * Automatically detects if the source is a [LazyList] and preserves lazy semantics.
 */
internal class StableIdAdapter<T> {
    private var nextId = 0
    // Sparse array: index -> stableId
    private var idMapping = mutableListOf<Int>()

    fun applyDelta(delta: Delta<T>): Delta<StableItem<T>> {
        // If idMapping is empty, we can't apply mutations - treat as Reload
        val effectiveChange = if (idMapping.isEmpty() && delta.change !is Change.Reload) {
            Change.Reload
        } else {
            delta.change
        }

        when (effectiveChange) {
            is Change.Reload -> {
                // On reload, regenerate all IDs
                idMapping.clear()
                repeat(delta.items.size) {
                    idMapping.add(nextId++)
                }
            }
            is Change.Mutations -> {
                for (mutation in effectiveChange.operations) {
                    applyMutation(mutation)
                }
            }
        }

        // Use lazy wrapper list - items are only accessed when get() is called
        // Detect LazyList source and preserve lazy semantics
        val wrappedList: List<StableItem<T>> = when (val items = delta.items) {
            is LazyList<T> -> StableItemLazyList(items, idMapping.toList())
            else -> StableItemList(items, idMapping.toList())
        }

        return Delta(wrappedList, effectiveChange)
    }

    private fun applyMutation(mutation: Mutation) {
        when (mutation) {
            is Mutation.Insert -> {
                // Insert new IDs at the given position
                for (i in 0 until mutation.count) {
                    idMapping.add(mutation.index + i, nextId++)
                }
            }
            is Mutation.Remove -> {
                // Remove IDs at the given positions
                for (i in 0 until mutation.count) {
                    idMapping.removeAt(mutation.index)
                }
            }
            is Mutation.Update -> {
                // IDs don't change on update - the item at that position keeps its ID
            }
            is Mutation.Move -> {
                // Move the ID from one position to another
                val id = idMapping.removeAt(mutation.fromIndex)
                idMapping.add(mutation.toIndex, id)
            }
        }
    }
}
