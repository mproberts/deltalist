package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*

import com.latenighthack.deltalist.get
import com.latenighthack.deltalist.toList
import com.latenighthack.deltalist.iterator
import com.latenighthack.deltalist.isEmpty
import com.latenighthack.deltalist.isNotEmpty
import com.latenighthack.deltalist.indices
import com.latenighthack.deltalist.map
import com.latenighthack.deltalist.filter
import com.latenighthack.deltalist.forEach
import com.latenighthack.deltalist.first
import com.latenighthack.deltalist.last
import com.latenighthack.deltalist.contains
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.applyChange
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The single highest-value test for a diffing library: apply the emitted mutations to
 * the previous list and assert the result equals the new list, for both curated and
 * randomized inputs. This validates [computeDiff] against the [applyChange] contract.
 */
class DiffOracleTest {

    private data class Item(val id: Int, val version: Int)

    /** Drives the public asDeltaList path over [states] and oracle-checks every delta. */
    private suspend fun verifySequence(states: List<List<Item>>) {
        val deltas: List<Delta<Item>> =
            flow { states.forEach { emit(it) } }.asDeltaList { it.id }.toList()

        assertEquals(states.size, deltas.size, "one delta per emission")
        assertTrue(deltas[0].change is Change.Reload, "first emission is always Reload")

        var reconstructed = emptyList<Item>()
        for ((i, delta) in deltas.withIndex()) {
            // applyChange must never throw and must reproduce the new snapshot exactly.
            reconstructed = applyChange(reconstructed, delta)
            assertEquals(
                states[i], reconstructed,
                "delta #$i ${delta.change} did not reconstruct ${states[i]} (from previous)"
            )
            // items carried on the delta must equal the snapshot it represents.
            assertEquals(states[i], delta.items.toList(), "delta #$i .items mismatch")
        }
    }

    @Test
    fun curatedCases() = runTest {
        fun items(vararg ids: Int) = ids.map { Item(it, 0) }
        verifySequence(listOf(items(1, 2, 3)))                              // single reload
        verifySequence(listOf(items(1, 2), items(1, 2, 3)))                // append
        verifySequence(listOf(items(1, 2), items(0, 1, 2)))                // prepend
        verifySequence(listOf(items(1, 3), items(1, 2, 3)))                // middle insert
        verifySequence(listOf(items(1, 2, 3), items(1, 2)))                // remove tail
        verifySequence(listOf(items(1, 2, 3), items(2, 3)))                // remove head
        verifySequence(listOf(items(1, 2, 3), items(1, 3)))                // remove middle
        verifySequence(listOf(items(1, 2, 3), items(2, 3, 1)))             // rotate
        verifySequence(listOf(items(1, 2, 3, 4), items(4, 3, 2, 1)))       // reverse
        verifySequence(listOf(items(1, 2), items(2, 1)))                   // swap
        verifySequence(listOf(items(1, 2, 3), emptyList()))               // to empty
        verifySequence(listOf(emptyList(), items(1, 2, 3)))               // from empty
        // remove + insert + update + move in one tick
        verifySequence(
            listOf(
                listOf(Item(1, 0), Item(2, 0), Item(3, 0), Item(4, 0)),
                listOf(Item(2, 0), Item(5, 0), Item(3, 1), Item(1, 0))
            )
        )
    }

    @Test
    fun duplicateIdsFallBackToReload() = runTest {
        val states = listOf(
            listOf(Item(1, 0), Item(1, 0), Item(2, 0)), // duplicate id 1
            listOf(Item(1, 0), Item(2, 0))
        )
        val deltas = flow { states.forEach { emit(it) } }.asDeltaList { it.id }.toList()
        // Non-injective id selector must degrade to Reload rather than emit a bad diff.
        assertTrue(deltas[1].change is Change.Reload)
        // And the carried items are still the new snapshot.
        assertEquals(states[1], deltas[1].items.toList())
    }

    @Test
    fun prependToLargeListEmitsSingleInsertNoMoves() = runTest {
        val old = (0 until 10_000).map { Item(it, 0) }
        val new = listOf(Item(-1, 0)) + old
        val deltas = flow { emit(old); emit(new) }.asDeltaList { it.id }.toList()
        val ops = (deltas[1].change as Change.Mutations).operations
        assertEquals(listOf(Mutation.Insert(0, 1)), ops, "prepend should be one Insert, zero Moves")
    }

    @Test
    fun fuzzRandomTransitions() = runTest {
        val seed = 0x5eed_1234L
        val rng = Random(seed)
        repeat(2_000) { trial ->
            val states = randomStates(rng)
            try {
                verifySequence(states)
            } catch (t: Throwable) {
                fail("fuzz trial #$trial (seed=$seed) failed for states=$states : ${t.message}")
            }
        }
    }

    private fun randomStates(rng: Random): List<List<Item>> {
        val idPool = (0..rng.nextInt(1, 9))
        val stateCount = rng.nextInt(2, 6)
        return (0 until stateCount).map {
            val ids = idPool.shuffled(rng).take(rng.nextInt(0, idPool.count() + 1))
            ids.map { id -> Item(id, rng.nextInt(0, 3)) } // version drives Update detection
        }
    }
}
