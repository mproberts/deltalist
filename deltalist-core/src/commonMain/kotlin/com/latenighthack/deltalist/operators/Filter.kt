package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.flow.flow

/**
 * Lazy filtered list that only accesses source items when get() is called.
 * The filteredIndices list maps from filtered index to source index.
 */
internal class FilteredList<T>(
    private val source: List<T>,
    private val filteredIndices: List<Int>
) : AbstractList<T>() {
    override val size: Int get() = filteredIndices.size

    override fun get(index: Int): T = source[filteredIndices[index]]
}

fun <T> DeltaFlow<T>.filterItems(predicate: (T) -> Boolean): DeltaFlow<T> = flow {
    var previousFilteredIndices: Set<Int> = emptySet()
    var previousSourceItems: List<T> = emptyList()

    collect { delta ->
        val sourceItems = delta.items

        // Build the set of source indices that pass the filter
        // Note: We need to check each item to know if it passes the filter,
        // but we defer accessing the actual item values until get() is called
        val currentFilteredIndices = buildFilteredIndices(sourceItems, predicate)
        val filteredIndicesList = currentFilteredIndices.sorted()

        val change = when (delta.change) {
            is Change.Reload -> Change.Reload
            is Change.Mutations -> {
                val mutations = translateMutations(
                    mutations = delta.change.operations,
                    previousSourceItems = previousSourceItems,
                    previousFilteredIndices = previousFilteredIndices,
                    currentSourceItems = sourceItems,
                    currentFilteredIndices = currentFilteredIndices,
                    predicate = predicate
                )
                if (mutations.isEmpty()) {
                    previousSourceItems = sourceItems
                    previousFilteredIndices = currentFilteredIndices
                    return@collect
                }
                Change.Mutations(mutations)
            }
        }

        previousSourceItems = sourceItems
        previousFilteredIndices = currentFilteredIndices

        // Use lazy filtered list - items are only accessed when get() is called
        emit(Delta(FilteredList(sourceItems, filteredIndicesList), change))
    }
}

/**
 * Builds the set of source indices that pass the filter.
 * This needs to check each item but is unavoidable for filtering.
 */
private fun <T> buildFilteredIndices(source: List<T>, predicate: (T) -> Boolean): Set<Int> {
    val result = mutableSetOf<Int>()
    for (i in source.indices) {
        if (predicate(source[i])) {
            result.add(i)
        }
    }
    return result
}

private fun <T> translateMutations(
    mutations: List<Mutation>,
    previousSourceItems: List<T>,
    previousFilteredIndices: Set<Int>,
    currentSourceItems: List<T>,
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
                    if (sourceIndex < currentSourceItems.size && predicate(currentSourceItems[sourceIndex])) {
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
                    val isInFilter = sourceIndex < currentSourceItems.size && predicate(currentSourceItems[sourceIndex])

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

                    // Adjust indices for the insertion
                    val adjustedToIndex = if (mutation.toIndex > mutation.fromIndex) {
                        mutation.toIndex - 1
                    } else {
                        mutation.toIndex
                    }

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
