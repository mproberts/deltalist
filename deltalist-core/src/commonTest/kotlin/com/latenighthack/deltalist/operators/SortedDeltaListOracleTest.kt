package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Property-based oracle for [asSortedDeltaList]: over random sequences of unordered snapshots,
 * every emitted [Change] must reconstruct the carried snapshot ([assertFlatOracle]) and that
 * snapshot must equal the deterministic sort of the input (by name, id tie-break). Snapshots are
 * sometimes passed as shuffled lists to stress the tie-break against iteration order.
 */
class SortedDeltaListOracleTest {

    private data class Profile(val id: Int, val name: String, val version: Int)

    private val totalOrder = compareBy<Profile> { it.name }.thenBy { it.id }

    private suspend fun verifySequence(states: List<Collection<Profile>>) {
        val deltas: List<Delta<Profile>> =
            flow { states.forEach { emit(it) } }
                .asSortedDeltaList(idSelector = { it.id }, sortBy = { it.name })
                .toList()

        assertEquals(states.size, deltas.size, "one delta per emission")
        assertTrue(deltas[0].change is Change.Reload, "first emission is always Reload")
        deltas.assertFlatOracle()

        for ((i, delta) in deltas.withIndex()) {
            assertEquals(
                states[i].sortedWith(totalOrder),
                delta.items.toList(),
                "delta #$i did not carry the deterministically sorted snapshot"
            )
        }
    }

    @Test
    fun fuzzRandomSnapshots() = runTest {
        val seed = 0x507ed_1234L
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

    /** Each snapshot has injective ids (a subset of the pool) so the minimal-diff path is exercised. */
    private fun randomStates(rng: Random): List<Collection<Profile>> {
        val names = listOf("A", "B", "C", "D") // small pool => frequent ties on name
        val idPool = (0..rng.nextInt(1, 9)).toList()
        val stateCount = rng.nextInt(2, 6)
        return (0 until stateCount).map {
            val ids = idPool.shuffled(rng).take(rng.nextInt(0, idPool.size + 1))
            val profiles = ids.map { id ->
                Profile(id, names[rng.nextInt(names.size)], rng.nextInt(0, 3))
            }
            // Vary the container/iteration order to stress deterministic ordering.
            if (rng.nextBoolean()) profiles.toSet() else profiles.shuffled(rng)
        }
    }
}
