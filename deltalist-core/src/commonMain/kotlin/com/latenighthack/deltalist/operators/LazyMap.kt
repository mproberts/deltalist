package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lazy transformation with acquire/release semantics for memory management.
 *
 * Thread-safe: Uses lock-free atomic operations to handle concurrent access
 * from delta producers (any thread) and UI consumers (typically main thread).
 *
 * Returns a DeltaList where items are backed by [LazyList]:
 * - Accessing items via `get()` computes and caches the transformation
 * - Platform adapters detect [LazyList] and call `release()` automatically
 * - Items track position changes across mutations
 *
 * Example:
 * ```kotlin
 * val items: DeltaList<MyItem> = ...
 * val transformed: DeltaList<TransformedItem> = items.lazyMap { transform(it) }
 *
 * // In Compose - adapters handle release automatically:
 * items(delta.items, key = { it.stableId }) { item ->
 *     // item is auto-acquired on access, auto-released on disposal
 *     MyCard(item)
 * }
 * ```
 *
 * @param transform The transformation function to apply lazily
 * @return A DeltaList backed by LazyList for automatic lifecycle management
 */
fun <T, R> DeltaList<T>.lazyMap(transform: (T) -> R): DeltaList<R> = flow {
    val state = LazyMapState(transform)

    collect { delta ->
        state.applyDelta(delta)
        emit(Delta(state.asList(), delta.change))
    }
}

/**
 * Simple transformation without caching - transforms on every access.
 * Use this when transformations are cheap or when you don't need retention semantics.
 *
 * This is similar to [map] but defers transformation to access time rather than
 * transforming all items upfront.
 */
fun <T, R> DeltaList<T>.deferredMap(transform: (T) -> R): DeltaList<R> = flow {
    collect { delta ->
        emit(Delta(
            items = SimpleLazyMapList(delta.items, transform),
            change = delta.change
        ))
    }
}

/**
 * Simple lazy list that transforms on every access (no caching).
 * Implements [SoftList] to propagate soft access from the source.
 */
internal class SimpleLazyMapList<T, R>(
    private val source: List<T>,
    private val transform: (T) -> R
) : AbstractList<R>(), SoftList<R> {
    override val size: Int get() = source.size
    override fun get(index: Int): R = transform(source[index])

    override fun softGet(index: Int): SoftValue<R>? {
        return if (source is SoftList<T>) {
            when (val soft = source.softGet(index)) {
                is SoftValue.Present -> SoftValue.Present(transform(soft.value))
                is SoftValue.NotLoaded -> soft
                null -> null
            }
        } else {
            if (index < 0 || index >= source.size) null
            else SoftValue.Present(transform(source[index]))
        }
    }
}

/**
 * Thread-safe state for lazy mapping with acquisition tracking.
 *
 * Uses atomic CAS operations to ensure consistency between:
 * - Delta producers calling applyDelta() (potentially background thread)
 * - UI consumers calling get()/release() (typically main thread)
 *
 * All operations are lock-free and use immutable snapshots.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class LazyMapState<S, T>(
    private val transform: (S) -> T
) {
    /**
     * Immutable snapshot of the current state.
     * Updated atomically via CAS.
     */
    private data class Snapshot<S, T>(
        val source: List<S>,
        val cache: Map<Int, T>
    )

    private val snapshot = AtomicReference<Snapshot<S, T>>(Snapshot(emptyList(), emptyMap()))

    /**
     * Apply a delta, atomically updating the source and transforming cache indices.
     * Uses CAS loop to handle concurrent modifications from get/release.
     */
    fun applyDelta(delta: Delta<S>) {
        while (true) {
            val current = snapshot.load()
            val newCache: Map<Int, T> = when (val change = delta.change) {
                is Change.Reload -> emptyMap()
                is Change.Mutations -> {
                    var cache = current.cache
                    for (mutation in change.operations) {
                        cache = applyMutationToCache(cache, mutation, delta.items)
                    }
                    cache
                }
            }
            val newSnapshot = Snapshot<S, T>(delta.items, newCache)
            if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                return
            }
            // CAS failed - another thread modified state, retry with fresh read
        }
    }

    private fun applyMutationToCache(
        cache: Map<Int, T>,
        mutation: Mutation,
        newSource: List<S>
    ): Map<Int, T> {
        return when (mutation) {
            is Mutation.Insert -> applyInsertToCache(cache, mutation)
            is Mutation.Remove -> applyRemoveToCache(cache, mutation)
            is Mutation.Update -> applyUpdateToCache(cache, mutation, newSource)
            is Mutation.Move -> applyMoveToCache(cache, mutation)
        }
    }

    private fun applyInsertToCache(cache: Map<Int, T>, mutation: Mutation.Insert): Map<Int, T> {
        return cache.mapKeys { (index, _) ->
            if (index >= mutation.index) index + mutation.count else index
        }
    }

    private fun applyRemoveToCache(cache: Map<Int, T>, mutation: Mutation.Remove): Map<Int, T> {
        return cache.mapNotNull { (index, value) ->
            when {
                index >= mutation.index + mutation.count -> (index - mutation.count) to value
                index >= mutation.index -> null // Item removed
                else -> index to value
            }
        }.toMap()
    }

    private fun applyUpdateToCache(
        cache: Map<Int, T>,
        mutation: Mutation.Update,
        newSource: List<S>
    ): Map<Int, T> {
        val result = cache.toMutableMap()
        for (i in mutation.index until (mutation.index + mutation.count)) {
            if (i in result && i < newSource.size) {
                result[i] = transform(newSource[i])
            }
        }
        return result
    }

    private fun applyMoveToCache(cache: Map<Int, T>, mutation: Mutation.Move): Map<Int, T> {
        val fromIndex = mutation.fromIndex
        val toIndex = mutation.toIndex

        if (fromIndex == toIndex) return cache

        val movedValue = cache[fromIndex]
        val result = mutableMapOf<Int, T>()

        for ((index, value) in cache) {
            if (index == fromIndex) continue // Handle separately

            val newIndex = if (fromIndex < toIndex) {
                when {
                    index > fromIndex && index <= toIndex -> index - 1
                    else -> index
                }
            } else {
                when {
                    index >= toIndex && index < fromIndex -> index + 1
                    else -> index
                }
            }
            result[newIndex] = value
        }

        if (movedValue != null) {
            result[toIndex] = movedValue
        }

        return result
    }

    /**
     * Returns a [LazyList] view that provides thread-safe lazy access.
     * Items are auto-acquired on access via `get()`.
     * The size and source are captured at creation time for consistency.
     */
    fun asList(): LazyList<T> {
        val currentSnapshot = snapshot.load()
        return LazyListImpl(currentSnapshot.source.size, currentSnapshot.source)
    }

    /**
     * LazyList implementation that auto-acquires items on access.
     */
    private inner class LazyListImpl(
        override val size: Int,
        private val sourceAtCreation: List<S>
    ) : AbstractList<T>(), LazyList<T>, SoftList<T> {

        override fun get(index: Int): T {
            // Auto-acquire: compute and cache if not already cached
            while (true) {
                val current = snapshot.load()

                // Fast path: already cached
                current.cache[index]?.let { return it }

                // Slow path: compute and cache
                if (index >= current.source.size) {
                    throw IndexOutOfBoundsException("Index $index out of bounds for size ${current.source.size}")
                }

                val value = transform(current.source[index])
                val newCache = current.cache + (index to value)
                val newSnapshot = current.copy(cache = newCache)

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return value
                }
                // CAS failed - state changed (delta applied or concurrent acquire)
                // Retry: will either find cached value or recompute with new source
            }
        }

        override fun release(index: Int) {
            while (true) {
                val current = snapshot.load()

                // Fast path: not cached
                if (!current.cache.containsKey(index)) return

                val newCache = current.cache - index
                val newSnapshot = current.copy(cache = newCache)

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return
                }
                // CAS failed - retry
            }
        }

        override fun releaseAll() {
            while (true) {
                val current = snapshot.load()

                // Fast path: nothing cached
                if (current.cache.isEmpty()) return

                val newSnapshot = current.copy(cache = emptyMap())

                if (snapshot.compareAndExchange(current, newSnapshot) === current) {
                    return
                }
                // CAS failed - retry
            }
        }

        override fun isAcquired(index: Int): Boolean {
            return snapshot.load().cache.containsKey(index)
        }

        override fun softGet(index: Int): SoftValue<T>? {
            if (index < 0 || index >= size) return null

            return if (sourceAtCreation is SoftList<S>) {
                when (val soft = sourceAtCreation.softGet(index)) {
                    is SoftValue.Present -> SoftValue.Present(get(index))
                    is SoftValue.NotLoaded -> soft
                    null -> null
                }
            } else {
                SoftValue.Present(get(index))
            }
        }
    }

    // For testing
    internal fun getCacheSize(): Int = snapshot.load().cache.size
    internal fun isIndexAcquired(index: Int): Boolean = snapshot.load().cache.containsKey(index)
}
