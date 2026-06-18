package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.asSoftList
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lazy filtered list that only accesses source items when get() is called.
 * The filteredIndices list maps from filtered index to source index.
 * Implements [SoftList] to propagate soft access from the source.
 *
 * When the source is a [SoftList] with an estimated size larger than loaded items,
 * this list will also report an estimated size based on the current filter ratio.
 * Accessing items beyond the loaded filtered items will trigger fetches on the source.
 *
 * @param source The source list to filter
 * @param filteredIndices Sorted list of source indices that pass the filter
 * @param sourceLoadedCount Number of actually loaded items in source (not estimated)
 * @param onUnloadedAccess Called when get() accesses an unloaded filtered index.
 *        Used by the filter operator to track pending accesses and cascade fetches.
 */
internal class FilteredList<T>(
    private val source: SoftList<T>,
    private val filteredIndices: List<Int>,
    private val sourceLoadedCount: Int,
    private val onUnloadedAccess: ((Int) -> Unit)? = null
) : AbstractSoftList<T>() {

    /** True when the source still has unloaded pages (so the filtered list isn't final). */
    private val sourceNotExhausted: Boolean
        get() = source.size > sourceLoadedCount

    // Estimate total filtered size based on current filter ratio.
    private val estimatedFilteredSize: Int
        get() {
            if (!sourceNotExhausted) {
                // Source fully loaded: the filtered size is exact.
                return filteredIndices.size
            }
            // Source has more pages. Always keep at least one placeholder beyond the known
            // matches so that a page matching few (or zero) items still renders a NotLoaded
            // row whose access cascades the next fetch. Without this, a first page that
            // matches nothing collapses size to 0 and pagination stalls forever even though
            // matches exist downstream.
            val minWithPlaceholder = filteredIndices.size + 1
            if (sourceLoadedCount == 0) {
                // No ratio yet; surface a single placeholder to kick off loading.
                return minWithPlaceholder
            }
            val filterRatio = filteredIndices.size.toDouble() / sourceLoadedCount
            val extrapolated = (source.size * filterRatio).toInt()
            return maxOf(minWithPlaceholder, extrapolated)
        }

    override val size: Int
        get() = maxOf(filteredIndices.size, estimatedFilteredSize)

    override fun softGet(index: Int): SoftValue<T>? {
        if (index < 0) return null

        if (index < filteredIndices.size) {
            // Within loaded filtered items
            return source.softGet(filteredIndices[index])
        }

        // Beyond loaded but within estimated size: a placeholder whose request() records
        // the pending access and cascades a fetch on the source. (Pure peek otherwise.)
        if (index < size) {
            val onAccess = onUnloadedAccess
            val sourceSoftValue = source.softGet(sourceLoadedCount)
            return SoftValue.NotLoaded {
                onAccess?.invoke(index)
                (sourceSoftValue as? SoftValue.NotLoaded)?.request()
            }
        }

        // Out of bounds
        return null
    }
}

/**
 * Adjust mutations to handle estimated size placeholders correctly.
 *
 * This function handles three cases:
 * 1. Convert Inserts within old estimated size to Updates (filling placeholders)
 * 2. Emit Remove mutations if estimated size shrinks (excess placeholders removed)
 * 3. Emit Insert mutations if estimated size grows beyond item coverage (new placeholders)
 *
 * @param mutations The raw mutations from translateMutations
 * @param previousLoadedCount Number of loaded (non-placeholder) items before the change
 * @param previousEstimatedSize Total size including placeholders before the change
 * @param newLoadedCount Number of loaded items after the change
 * @param newEstimatedSize Total size including placeholders after the change
 */
private fun adjustMutationsForPlaceholders(
    mutations: List<Mutation>,
    previousLoadedCount: Int,
    previousEstimatedSize: Int,
    newLoadedCount: Int,
    newEstimatedSize: Int
): List<Mutation> {
    val result = mutableListOf<Mutation>()

    // Step 1: Process mutations - convert Inserts within old estimated to Updates
    if (previousEstimatedSize > previousLoadedCount) {
        for (mutation in mutations) {
            when (mutation) {
                is Mutation.Insert -> {
                    // Inserts at positions within the old estimated size are filling placeholders
                    if (mutation.index < previousEstimatedSize) {
                        // How many of these "inserts" are actually replacing placeholders?
                        val placeholderEnd = previousEstimatedSize
                        val updateEnd = minOf(mutation.index + mutation.count, placeholderEnd)
                        val updateCount = updateEnd - mutation.index

                        if (updateCount > 0) {
                            result.add(Mutation.Update(mutation.index, updateCount))
                        }

                        // Any inserts beyond the old estimated size are true inserts
                        val insertCount = mutation.count - updateCount
                        if (insertCount > 0) {
                            result.add(Mutation.Insert(updateEnd, insertCount))
                        }
                    } else {
                        // Insert is entirely beyond old estimated size - true insert
                        result.add(mutation)
                    }
                }
                else -> result.add(mutation)
            }
        }
    } else {
        // No placeholders existed before - keep mutations as-is
        result.addAll(mutations)
    }

    // Step 2: Handle placeholder count changes
    // Calculate how the total size needs to change after accounting for item mutations
    //
    // The mutations in result already handle:
    // - Updates: placeholders converted to real items (don't change total size)
    // - Inserts: new items beyond estimated size (increase total size)
    //
    // We need to emit Remove/Insert for the remaining size difference.
    //
    // Example: 50 → 30 with 1 new loaded item
    // - Update(5, 1): placeholder at 5 becomes real (size still 50)
    // - Need Remove(30, 20) to shrink from 50 to 30

    // Count how many positions the current mutations add/remove
    var mutationSizeChange = 0
    for (mutation in result) {
        when (mutation) {
            is Mutation.Insert -> mutationSizeChange += mutation.count
            is Mutation.Remove -> mutationSizeChange -= mutation.count
            else -> {} // Update doesn't change size
        }
    }

    // Calculate net size change needed
    val totalSizeChange = newEstimatedSize - previousEstimatedSize
    val remainingSizeChange = totalSizeChange - mutationSizeChange

    if (remainingSizeChange < 0) {
        // Need to remove positions (excess placeholders)
        val removeCount = -remainingSizeChange
        result.add(Mutation.Remove(newEstimatedSize, removeCount))
    } else if (remainingSizeChange > 0) {
        // Need to add positions (new placeholders)
        result.add(Mutation.Insert(previousEstimatedSize + mutationSizeChange, remainingSizeChange))
    }

    return result
}

fun <T> DeltaList<T>.filterItems(predicate: (T) -> Boolean): DeltaList<T> = flow {
    var previousFilteredIndices: Set<Int> = emptySet()
    var previousFilteredSize: Int = 0
    var previousSourceItems: SoftList<T> = emptyList<T>().asSoftList()

    collect { delta ->
        val sourceItems = delta.items

        // Build the set of source indices that pass the filter
        // Note: We need to check each item to know if it passes the filter,
        // but we defer accessing the actual item values until get() is called
        val (currentFilteredIndices, sourceLoadedCount) = buildFilteredIndices(sourceItems, predicate)
        val filteredIndicesList = currentFilteredIndices.sorted()

        // Save previous loaded count for placeholder adjustment
        val prevLoadedCount = previousFilteredIndices.size

        // If previousSourceItems is empty, we can't translate mutations - treat as Reload
        val change = if (previousSourceItems.size == 0 && delta.change !is Change.Reload) {
            Change.Reload
        } else {
            when (delta.change) {
                is Change.Reload -> Change.Reload
                is Change.Mutations -> {
                    val rawMutations = translateMutations(
                        mutations = delta.change.operations,
                        previousSourceItems = previousSourceItems,
                        previousFilteredIndices = previousFilteredIndices,
                        currentSourceItems = sourceItems,
                        currentFilteredIndices = currentFilteredIndices,
                        predicate = predicate
                    )
                    // Create filtered list to get new estimated size
                    val newFilteredList = FilteredList(sourceItems, filteredIndicesList, sourceLoadedCount)
                    val newLoadedCount = currentFilteredIndices.size
                    val newEstimatedSize = newFilteredList.size

                    // Adjust mutations: handle placeholder transitions and size changes
                    val mutations = adjustMutationsForPlaceholders(
                        rawMutations,
                        prevLoadedCount,
                        previousFilteredSize,
                        newLoadedCount,
                        newEstimatedSize
                    )
                    if (mutations.isEmpty()) {
                        previousSourceItems = sourceItems
                        previousFilteredIndices = currentFilteredIndices
                        previousFilteredSize = newEstimatedSize
                        return@collect
                    }
                    Change.Mutations(mutations)
                }
            }
        }

        previousSourceItems = sourceItems
        previousFilteredIndices = currentFilteredIndices

        // Use lazy filtered list - items are only accessed when get() is called
        val filteredList = FilteredList(sourceItems, filteredIndicesList, sourceLoadedCount)
        previousFilteredSize = filteredList.size
        emit(Delta(filteredList, change))
    }
}

/**
 * Sealed class to represent events in the dynamic filter merge.
 */
private sealed class FilterEvent<out T> {
    data class UpstreamDelta<T>(val delta: Delta<T>) : FilterEvent<T>()
    data class PredicateChanged<T>(val predicate: (T) -> Boolean) : FilterEvent<T>()
}

/**
 * Filters items dynamically based on a predicate that can change over time.
 * When [predicateFlow] emits a new predicate, all current items are re-filtered
 * and a [Change.Reload] is emitted.
 *
 * This operator automatically cascades fetches for paginated sources: when the UI
 * accesses a filtered index that's not yet loaded, the operator tracks that access
 * and continues fetching from the source until the index is satisfied or the source
 * is exhausted.
 *
 * This is useful when the filter criteria can change (e.g., from user input)
 * and the filtered list should update immediately.
 *
 * @param predicateFlow A [Flow] that emits predicate functions. Each emission
 *        triggers a re-filter of the current items.
 */
@OptIn(ExperimentalAtomicApi::class)
fun <T> DeltaList<T>.filterItemsDynamic(
    predicateFlow: kotlinx.coroutines.flow.Flow<(T) -> Boolean>
): DeltaList<T> = flow {
    var currentSourceItems: SoftList<T> = emptyList<T>().asSoftList()
    var currentSourceLoadedCount: Int = 0
    var previousFilteredIndices: Set<Int> = emptySet()
    var previousFilteredSize: Int = 0 // Track estimated size for placeholder handling
    var currentPredicate: ((T) -> Boolean)? = null
    var pendingDelta: Delta<T>? = null // Store delta if it arrives before predicate

    // Track filtered indices that have been accessed but not yet loaded.
    // This enables cascading fetches: when the UI accesses an unloaded filtered item,
    // we keep fetching from the source until that item is satisfied.
    //
    // Accessed from two contexts with no happens-before relation: the collector coroutine
    // (cascade/predicate handling) and FilteredList.get() (any thread, e.g. the UI thread
    // during a layout pass). Hold it in an atomic and mutate via CAS so updates can't be
    // lost to a data race.
    val pendingAccessIndices = AtomicReference<Set<Int>>(emptySet())

    fun addPendingAccess(index: Int) {
        while (true) {
            val current = pendingAccessIndices.load()
            if (index in current) return
            if (pendingAccessIndices.compareAndExchange(current, current + index) === current) return
        }
    }

    fun setPendingAccess(value: Set<Int>) {
        pendingAccessIndices.store(value)
    }

    // Callback for FilteredList to notify when unloaded items are accessed
    val onUnloadedAccess: (Int) -> Unit = { index ->
        addPendingAccess(index)
    }

    // Helper to create FilteredList with access tracking
    fun createFilteredList(source: SoftList<T>, indices: List<Int>, loadedCount: Int): FilteredList<T> {
        return FilteredList(source, indices, loadedCount, onUnloadedAccess)
    }

    // Cascade fetches: after emitting a delta, check if pending accesses are still
    // NotLoaded and trigger another fetch if needed
    fun cascadeFetchesIfNeeded(filteredList: FilteredList<T>, source: SoftList<T>, sourceLoadedCount: Int) {
        // Find indices that are still not loaded
        val stillPending = pendingAccessIndices.load().filter { index ->
            index < filteredList.size && filteredList.softGet(index) is SoftValue.NotLoaded
        }

        // Update pending set
        setPendingAccess(stillPending.toSet())

        // If there are still pending accesses and the source has more data, request the next
        // unloaded source item (its placeholder's request() drives the fetch).
        if (stillPending.isNotEmpty() && sourceLoadedCount < source.size) {
            (source.softGet(sourceLoadedCount) as? SoftValue.NotLoaded)?.request()
        }
    }

    // Merge upstream deltas with predicate changes
    val upstream = this@filterItemsDynamic

    merge(
        upstream.map { delta -> FilterEvent.UpstreamDelta(delta) },
        predicateFlow.map { predicate -> FilterEvent.PredicateChanged(predicate) }
    ).collect { event ->
        when (event) {
            is FilterEvent.PredicateChanged -> {
                currentPredicate = event.predicate

                // Clear pending accesses on predicate change - they're no longer valid
                setPendingAccess(emptySet())

                // Check if we have a pending delta that arrived before the predicate
                val deltaToProcess = pendingDelta
                pendingDelta = null

                if (deltaToProcess != null) {
                    // Process the pending delta with the new predicate
                    val sourceItems = deltaToProcess.items
                    currentSourceItems = sourceItems

                    val (filteredIndices, loadedCount) = buildFilteredIndices(sourceItems, event.predicate)
                    val filteredIndicesList = filteredIndices.sorted()

                    previousFilteredIndices = filteredIndices
                    currentSourceLoadedCount = loadedCount

                    val filteredList = createFilteredList(sourceItems, filteredIndicesList, loadedCount)
                    previousFilteredSize = filteredList.size
                    emit(Delta(filteredList, Change.Reload))
                    cascadeFetchesIfNeeded(filteredList, sourceItems, loadedCount)
                } else if (currentSourceItems.size > 0) {
                    // Predicate changed - re-filter current items and emit Reload
                    val (filteredIndices, loadedCount) = buildFilteredIndices(currentSourceItems, event.predicate)
                    val filteredIndicesList = filteredIndices.sorted()

                    previousFilteredIndices = filteredIndices
                    currentSourceLoadedCount = loadedCount

                    val filteredList = createFilteredList(currentSourceItems, filteredIndicesList, loadedCount)
                    previousFilteredSize = filteredList.size
                    emit(Delta(filteredList, Change.Reload))
                    cascadeFetchesIfNeeded(filteredList, currentSourceItems, loadedCount)
                }
            }

            is FilterEvent.UpstreamDelta -> {
                val delta = event.delta
                val predicate = currentPredicate

                if (predicate == null) {
                    // Store delta to process when predicate arrives
                    pendingDelta = delta
                    return@collect
                }

                val sourceItems = delta.items
                currentSourceItems = sourceItems

                // Save previous loaded count for placeholder adjustment
                val prevLoadedCount = previousFilteredIndices.size

                val (currentFilteredIndices, loadedCount) = buildFilteredIndices(sourceItems, predicate)
                val filteredIndicesList = currentFilteredIndices.sorted()
                currentSourceLoadedCount = loadedCount

                // If previousFilteredIndices is empty, we can't translate mutations - treat as Reload
                val change = if (previousFilteredIndices.isEmpty() && delta.change !is Change.Reload) {
                    Change.Reload
                } else {
                    when (delta.change) {
                        is Change.Reload -> Change.Reload
                        is Change.Mutations -> {
                            val rawMutations = translateMutations(
                                mutations = delta.change.operations,
                                previousSourceItems = currentSourceItems,
                                previousFilteredIndices = previousFilteredIndices,
                                currentSourceItems = sourceItems,
                                currentFilteredIndices = currentFilteredIndices,
                                predicate = predicate
                            )
                            // Create filtered list to get new estimated size
                            val newFilteredList = createFilteredList(sourceItems, filteredIndicesList, loadedCount)
                            val newLoadedCount = currentFilteredIndices.size
                            val newEstimatedSize = newFilteredList.size

                            // Adjust mutations: handle placeholder transitions and size changes
                            val mutations = adjustMutationsForPlaceholders(
                                rawMutations,
                                prevLoadedCount,
                                previousFilteredSize,
                                newLoadedCount,
                                newEstimatedSize
                            )
                            if (mutations.isEmpty()) {
                                previousFilteredIndices = currentFilteredIndices
                                previousFilteredSize = newEstimatedSize
                                // Still cascade fetches even if no mutations to emit
                                cascadeFetchesIfNeeded(newFilteredList, sourceItems, loadedCount)
                                return@collect
                            }
                            Change.Mutations(mutations)
                        }
                    }
                }

                previousFilteredIndices = currentFilteredIndices

                val filteredList = createFilteredList(sourceItems, filteredIndicesList, loadedCount)
                previousFilteredSize = filteredList.size
                emit(Delta(filteredList, change))
                cascadeFetchesIfNeeded(filteredList, sourceItems, loadedCount)
            }
        }
    }
}

/**
 * Result of building filtered indices.
 * @property filteredIndices Set of source indices that pass the filter
 * @property loadedCount Number of source items that were actually loaded (not estimated)
 */
private data class FilteredIndicesResult(
    val filteredIndices: Set<Int>,
    val loadedCount: Int
)

/**
 * Builds the set of source indices that pass the filter.
 * For [SoftList] sources, uses [SoftList.softGet] to avoid triggering fetches.
 * Items that are not yet loaded ([SoftValue.NotLoaded]) are skipped.
 *
 * @return A pair of (filtered indices, loaded item count)
 */
private fun <T> buildFilteredIndices(source: SoftList<T>, predicate: (T) -> Boolean): FilteredIndicesResult {
    val result = mutableSetOf<Int>()
    var loadedCount = 0

    // Use soft access to avoid triggering pagination fetches.
    for (i in 0 until source.size) {
        when (val soft = source.softGet(i)) {
            is SoftValue.Present -> {
                loadedCount++
                if (predicate(soft.value)) {
                    result.add(i)
                }
            }
            is SoftValue.NotLoaded -> {
                // Skip unloaded items - they'll be included when loaded
            }
            null -> {
                // Out of bounds, skip
            }
        }
    }

    return FilteredIndicesResult(result, loadedCount)
}

/**
 * Checks if an item at the given index passes the predicate, using soft access if available.
 * Returns null if the item is not loaded (for SoftList) or out of bounds.
 */
private fun <T> checkPredicateSoft(
    source: SoftList<T>,
    index: Int,
    predicate: (T) -> Boolean
): Boolean? {
    if (index < 0) return null
    return when (val soft = source.softGet(index)) {
        is SoftValue.Present -> predicate(soft.value)
        is SoftValue.NotLoaded -> null // Not loaded, can't determine
        null -> null // Out of bounds
    }
}

private fun <T> translateMutations(
    mutations: List<Mutation>,
    previousSourceItems: SoftList<T>,
    previousFilteredIndices: Set<Int>,
    currentSourceItems: SoftList<T>,
    currentFilteredIndices: Set<Int>,
    predicate: (T) -> Boolean
): List<Mutation> {
    val result = mutableListOf<Mutation>()

    // Track the evolving state as we process each mutation
    var workingFilteredIndices = previousFilteredIndices.toMutableSet()
    var workingSourceSize = previousSourceItems.size

    for (mutation in mutations) {
        when (mutation) {
            is Mutation.Insert -> {
                // Shift existing filtered indices at or after the insertion point
                workingFilteredIndices = workingFilteredIndices.map { idx ->
                    if (idx >= mutation.index) idx + mutation.count else idx
                }.toMutableSet()

                // Check which inserted items pass the filter
                var filteredInsertCount = 0
                var firstFilteredInsertIndex = -1

                for (i in 0 until mutation.count) {
                    val sourceIndex = mutation.index + i
                    val passesFilter = checkPredicateSoft(currentSourceItems, sourceIndex, predicate)
                    if (passesFilter == true) {
                        workingFilteredIndices.add(sourceIndex)
                        if (firstFilteredInsertIndex == -1) {
                            firstFilteredInsertIndex = sourceIndexToFilteredIndex(sourceIndex, workingFilteredIndices)
                        }
                        filteredInsertCount++
                    }
                }

                if (filteredInsertCount > 0) {
                    result.add(Mutation.Insert(firstFilteredInsertIndex, filteredInsertCount))
                }

                workingSourceSize += mutation.count
            }

            is Mutation.Remove -> {
                // Find which removed items were in the filter
                var filteredRemoveCount = 0
                var firstFilteredRemoveIndex = -1

                for (i in 0 until mutation.count) {
                    val sourceIndex = mutation.index + i
                    if (sourceIndex in workingFilteredIndices) {
                        if (firstFilteredRemoveIndex == -1) {
                            firstFilteredRemoveIndex = sourceIndexToFilteredIndex(sourceIndex, workingFilteredIndices)
                        }
                        filteredRemoveCount++
                        workingFilteredIndices.remove(sourceIndex)
                    }
                }

                if (filteredRemoveCount > 0) {
                    result.add(Mutation.Remove(firstFilteredRemoveIndex, filteredRemoveCount))
                }

                // Shift remaining filtered indices
                workingFilteredIndices = workingFilteredIndices.map { idx ->
                    if (idx > mutation.index) idx - mutation.count else idx
                }.toMutableSet()

                workingSourceSize -= mutation.count
            }

            is Mutation.Update -> {
                for (i in 0 until mutation.count) {
                    val sourceIndex = mutation.index + i
                    val wasInFilter = sourceIndex in workingFilteredIndices
                    val isInFilter = checkPredicateSoft(currentSourceItems, sourceIndex, predicate) == true

                    when {
                        wasInFilter && isInFilter -> {
                            // Item still passes filter - emit update
                            val filteredIndex = sourceIndexToFilteredIndex(sourceIndex, workingFilteredIndices)
                            result.add(Mutation.Update(filteredIndex, 1))
                        }
                        wasInFilter && !isInFilter -> {
                            // Item no longer passes filter - emit remove
                            val filteredIndex = sourceIndexToFilteredIndex(sourceIndex, workingFilteredIndices)
                            result.add(Mutation.Remove(filteredIndex, 1))
                            workingFilteredIndices.remove(sourceIndex)
                        }
                        !wasInFilter && isInFilter -> {
                            // Item now passes filter - emit insert
                            workingFilteredIndices.add(sourceIndex)
                            val filteredIndex = sourceIndexToFilteredIndex(sourceIndex, workingFilteredIndices)
                            result.add(Mutation.Insert(filteredIndex, 1))
                        }
                        // !wasInFilter && !isInFilter -> no change to filtered list
                    }
                }
            }

            is Mutation.Move -> {
                // Handle move as remove + insert for simplicity
                // This preserves correctness even if not optimal for animations
                val wasInFilter = mutation.fromIndex in workingFilteredIndices

                if (wasInFilter) {
                    val fromFilteredIndex = sourceIndexToFilteredIndex(mutation.fromIndex, workingFilteredIndices)
                    workingFilteredIndices.remove(mutation.fromIndex)

                    // Adjust indices for the removal
                    workingFilteredIndices = workingFilteredIndices.map { idx ->
                        if (idx > mutation.fromIndex) idx - 1 else idx
                    }.toMutableSet()

                    // Move's toIndex is already a post-removal running coordinate (the applier
                    // does removeAt(from) then add(to)), so it indexes the working source space
                    // directly — no off-by-one adjustment.
                    val adjustedToIndex = mutation.toIndex

                    workingFilteredIndices = workingFilteredIndices.map { idx ->
                        if (idx >= adjustedToIndex) idx + 1 else idx
                    }.toMutableSet()

                    workingFilteredIndices.add(adjustedToIndex)
                    val toFilteredIndex = sourceIndexToFilteredIndex(adjustedToIndex, workingFilteredIndices)

                    if (fromFilteredIndex != toFilteredIndex) {
                        result.add(Mutation.Move(fromFilteredIndex, toFilteredIndex, 1))
                    }
                }
            }
        }
    }

    return coalesceMutations(result)
}

private fun sourceIndexToFilteredIndex(sourceIndex: Int, filteredIndices: Set<Int>): Int {
    return filteredIndices.count { it < sourceIndex }
}

private fun coalesceMutations(mutations: List<Mutation>): List<Mutation> {
    if (mutations.size <= 1) return mutations

    val result = mutableListOf<Mutation>()
    var i = 0

    while (i < mutations.size) {
        val current = mutations[i]

        // Try to coalesce consecutive mutations of the same type at adjacent indices
        when (current) {
            is Mutation.Insert -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    if (next is Mutation.Insert && next.index == current.index + count) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Insert(current.index, count))
                i = j
            }
            is Mutation.Remove -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    // Consecutive removes at the same index (since items shift down)
                    if (next is Mutation.Remove && next.index == current.index) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Remove(current.index, count))
                i = j
            }
            is Mutation.Update -> {
                var count = current.count
                var j = i + 1
                while (j < mutations.size) {
                    val next = mutations[j]
                    if (next is Mutation.Update && next.index == current.index + count) {
                        count += next.count
                        j++
                    } else {
                        break
                    }
                }
                result.add(Mutation.Update(current.index, count))
                i = j
            }
            is Mutation.Move -> {
                result.add(current)
                i++
            }
        }
    }

    return result
}
