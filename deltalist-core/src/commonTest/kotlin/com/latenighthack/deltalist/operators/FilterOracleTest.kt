package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz-oracle sweep over [filterItems]. Drives a fully-loaded mutable source through random valid
 * single-op mutations and asserts the filtered stream reconstructs itself via [applyChange] —
 * directly stressing the mutation-index translation, which is the operator's riskiest logic.
 */
class FilterOracleTest {

    @Test
    fun fuzzKeepEven() = sweep(seed = 0x1111) { it % 2 == 0 }

    @Test
    fun fuzzDropMultiplesOfThree() = sweep(seed = 0x2222) { it % 3 != 0 }

    @Test
    fun fuzzKeepAll() = sweep(seed = 0x3333) { true }

    private fun sweep(seed: Int, predicate: (Int) -> Boolean) = runTest {
        val rng = Random(seed.toLong())
        repeat(800) { trial ->
            var sourceList = (0 until rng.nextInt(0, 5)).map { rng.nextInt(0, 12) }
            var counter = 100
            val next = { rng.nextInt(0, 12).also { counter++ } }
            val source = MutableStateFlow(Delta(sourceList, Change.Reload))

            try {
                val deltas = collectDriven(source.filterItems(predicate)) {
                    repeat(rng.nextInt(2, 9)) {
                        val (nl, op) = randomFlatStep(sourceList, rng, next)
                        sourceList = nl
                        step(source, Delta(nl, Change.Mutations(op)))
                    }
                }
                deltas.assertFlatOracle()
                assertEquals(sourceList.filter(predicate), deltas.last().items.softLoadedItems())
            } catch (t: Throwable) {
                throw AssertionError("filter fuzz trial #$trial (seed=$seed) failed: ${t.message}")
            }
        }
    }

    /**
     * Regression lock for the filter Move-translation bug the oracle sweep surfaced: for a
     * pass-through filter, a source `Move(0,2)` on `[10,20,30]` must reproduce `[20,30,10]`.
     * Previously `filterItems` emitted a filtered `Move(0,1)` (a bad off-by-one in the
     * post-removal toIndex), yielding `[20,10,30]`.
     */
    @Test
    fun moveTranslationReconstructsExactly() = runTest {
        val source = MutableStateFlow(Delta(listOf(10, 20, 30), Change.Reload))
        val deltas = collectDriven(source.filterItems { true }) {
            step(source, Delta(listOf(20, 30, 10), Change.Mutations(Mutation.Move(0, 2, 1))))
        }
        deltas.assertFlatOracle()
        assertEquals(listOf(20, 30, 10), deltas.last().items.softLoadedItems())
    }
}
