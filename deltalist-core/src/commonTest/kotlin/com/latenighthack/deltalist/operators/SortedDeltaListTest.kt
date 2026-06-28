package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Curated behaviour tests for [asSortedDeltaList]: an unordered collection projected into a
 * sorted [com.latenighthack.deltalist.DeltaList] should emit targeted mutations (not reloads)
 * as items are added, removed, or changed.
 */
class SortedDeltaListTest {

    private data class Profile(val id: Int, val name: String, val extra: Int = 0)

    /** Sorts each snapshot by name (the [asSortedDeltaList] selector overload) and collects deltas. */
    private suspend fun collect(states: List<Collection<Profile>>): List<Delta<Profile>> =
        flow { states.forEach { emit(it) } }
            .asSortedDeltaList(idSelector = { it.id }, sortBy = { it.name })
            .toList()

    private fun ops(delta: Delta<Profile>) = (delta.change as Change.Mutations).operations

    @Test
    fun firstEmissionIsReloadSortedByName() = runTest {
        val deltas = collect(
            listOf(setOf(Profile(3, "Charlie"), Profile(1, "Alice"), Profile(2, "Bob")))
        )

        assertEquals(1, deltas.size)
        assertTrue(deltas[0].change is Change.Reload)
        assertEquals(listOf(1, 2, 3), deltas[0].items.toList().map { it.id })
        deltas.assertFlatOracle()
    }

    @Test
    fun addingAnItemEmitsSingleInsertAtSortedIndex() = runTest {
        val deltas = collect(
            listOf(
                setOf(Profile(1, "Alice"), Profile(3, "Charlie")),
                setOf(Profile(1, "Alice"), Profile(2, "Bob"), Profile(3, "Charlie"))
            )
        )

        assertEquals(listOf(Mutation.Insert(1, 1)), ops(deltas[1]))
        assertEquals(listOf(1, 2, 3), deltas[1].items.toList().map { it.id })
        deltas.assertFlatOracle()
    }

    @Test
    fun removingAnItemEmitsSingleRemove() = runTest {
        val deltas = collect(
            listOf(
                setOf(Profile(1, "Alice"), Profile(2, "Bob"), Profile(3, "Charlie")),
                setOf(Profile(1, "Alice"), Profile(3, "Charlie"))
            )
        )

        assertEquals(listOf(Mutation.Remove(1, 1)), ops(deltas[1]))
        deltas.assertFlatOracle()
    }

    @Test
    fun contentChangeWithoutKeyChangeEmitsUpdateNoMove() = runTest {
        val deltas = collect(
            listOf(
                setOf(Profile(1, "Alice"), Profile(2, "Bob", extra = 0), Profile(3, "Charlie")),
                setOf(Profile(1, "Alice"), Profile(2, "Bob", extra = 1), Profile(3, "Charlie"))
            )
        )

        assertEquals(listOf(Mutation.Update(1, 1)), ops(deltas[1]))
        assertTrue(ops(deltas[1]).none { it is Mutation.Move })
        deltas.assertFlatOracle()
    }

    @Test
    fun renameThatChangesSortPositionMovesAndUpdates() = runTest {
        val deltas = collect(
            listOf(
                setOf(Profile(1, "Alice"), Profile(2, "Bob"), Profile(3, "Charlie")),
                setOf(Profile(1, "Zoe"), Profile(2, "Bob"), Profile(3, "Charlie"))
            )
        )

        // Engine reuse means a cascading rename may emit several Moves; assert via reconstruction
        // rather than exact move count, but verify it stayed incremental (not a Reload).
        assertTrue(deltas[1].change is Change.Mutations)
        assertTrue(ops(deltas[1]).any { it is Mutation.Move }, "rename should move the item")
        assertTrue(ops(deltas[1]).any { it is Mutation.Update }, "rename should update content")
        assertEquals(listOf(2, 3, 1), deltas[1].items.toList().map { it.id })
        deltas.assertFlatOracle()
    }

    @Test
    fun equalKeysOrderDeterministicallyByIdRegardlessOfIterationOrder() = runTest {
        val deltas = collect(
            listOf(
                // Tied on name "Sam"; id tie-break fixes order to (1, 2).
                setOf(Profile(1, "Sam"), Profile(2, "Sam"), Profile(3, "Zoe")),
                // Same tied pair, reversed iteration order, plus a remove + insert. Without the
                // id tie-break the stable sort would honour this order and emit a spurious Move.
                listOf(Profile(2, "Sam"), Profile(1, "Sam"), Profile(4, "Aaron"))
            )
        )

        assertEquals(listOf(4, 1, 2), deltas[1].items.toList().map { it.id })
        assertTrue(ops(deltas[1]).none { it is Mutation.Move }, "tied pair must not produce a Move")
        deltas.assertFlatOracle()
    }

    @Test
    fun emptyToNonEmptyToEmpty() = runTest {
        val deltas = collect(
            listOf(
                emptySet(),
                setOf(Profile(2, "Bob")),
                emptySet()
            )
        )

        assertTrue(deltas[0].change is Change.Reload)
        assertEquals(listOf(Mutation.Insert(0, 1)), ops(deltas[1]))
        assertEquals(listOf(Mutation.Remove(0, 1)), ops(deltas[2]))
        deltas.assertFlatOracle()
    }

    @Test
    fun duplicateIdsInASnapshotFallBackToReload() = runTest {
        val deltas = collect(
            listOf(
                setOf(Profile(1, "Alice"), Profile(2, "Bob")),
                // Two distinct elements sharing id 1: a minimal diff isn't well-defined.
                setOf(Profile(1, "Alice"), Profile(1, "Zed"))
            )
        )

        assertTrue(deltas[1].change is Change.Reload)
        assertEquals(listOf("Alice", "Zed"), deltas[1].items.toList().map { it.name })
    }

    @Test
    fun comparatorOverloadSupportsDescendingOrder() = runTest {
        val deltas = flow { emit(setOf(Profile(1, "Alice"), Profile(2, "Bob"), Profile(3, "Charlie"))) }
            .asSortedDeltaList(idSelector = { it.id }, comparator = compareByDescending { it.name })
            .toList()

        assertEquals(listOf(3, 2, 1), deltas[0].items.toList().map { it.id })
    }
}
