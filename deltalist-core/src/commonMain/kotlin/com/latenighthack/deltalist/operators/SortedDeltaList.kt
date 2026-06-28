package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.DeltaList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Projects a watched **unordered** collection into the index-space of a **sorted**
 * [DeltaList], emitting a minimal changeset as the collection changes.
 *
 * Each upstream snapshot is sorted with [comparator] and diffed against the previous sorted
 * snapshot via [asDeltaList], so adding, removing, or changing an item produces targeted
 * Insert / Remove / Update / Move mutations rather than a full reload. The canonical use is a
 * set of profiles displayed alphabetically:
 *
 * ```
 * data class Profile(val id: String, val displayName: String)
 *
 * val profiles: Flow<Set<Profile>> = ...
 * val sorted: DeltaList<Profile> = profiles.asSortedDeltaList(
 *     idSelector = { it.id },
 *     comparator = compareBy { it.displayName }
 * )
 * ```
 *
 * [idSelector] must be injective within any single snapshot. It is appended as a tie-break so
 * that items comparing equal under [comparator] receive a stable, total order independent of the
 * source collection's iteration order — without it, two equal-key items could swap between
 * snapshots and produce spurious Moves. If a snapshot does contain duplicate ids, the underlying
 * diff degrades to a Reload for that emission (see [asDeltaList]).
 *
 * Content changes are detected by `equals`, so items should have structural equality (e.g. a data
 * class) for Update mutations to be emitted when an item's contents change.
 */
fun <T, ID : Comparable<ID>> Flow<Collection<T>>.asSortedDeltaList(
    idSelector: (T) -> ID,
    comparator: Comparator<T>
): DeltaList<T> {
    val totalOrder = comparator.thenBy(idSelector)
    return map { it.sortedWith(totalOrder) }.asDeltaList(idSelector)
}

/**
 * Sort-key convenience for [asSortedDeltaList]: sorts by the [Comparable] returned from [sortBy]
 * (e.g. `sortBy = { it.displayName }`). Delegates to the [Comparator] overload, so the same
 * identity, tie-break, and minimal-changeset contracts apply.
 */
fun <T, ID : Comparable<ID>, R : Comparable<R>> Flow<Collection<T>>.asSortedDeltaList(
    idSelector: (T) -> ID,
    sortBy: (T) -> R
): DeltaList<T> = asSortedDeltaList(idSelector, compareBy(sortBy))
