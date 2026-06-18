@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.Section
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

/**
 * Fuzz-oracle sweep over [flatten]. Applies random section- and item-level operations to a
 * sectioned source and asserts the flattened (header + items) stream reconstructs itself via
 * [applyChange] — stressing Flatten's lowering of section/item mutations into flat coordinates.
 */
class FlattenOracleTest {

    @Test
    fun fuzz() = runTest {
        val rng = Random(0x7A7A)
        repeat(400) { trial ->
            val list = mutableSectionedDeltaListOf(
                List(rng.nextInt(1, 3)) { s -> Section("S$s", List(rng.nextInt(0, 3)) { s * 10 + it }) }
            )
            var counter = 1_000

            try {
                val deltas = collectDriven(list.flatten(header = { "H:$it" }, item = { "I:$it" })) {
                    repeat(rng.nextInt(2, 10)) {
                        val sectionCount = list.value.size
                        when (rng.nextInt(if (sectionCount == 0) 2 else 6)) {
                            0 -> list.appendSection("S${counter++}", listOf(counter++))
                            1 -> if (sectionCount > 0) list.insertSection(rng.nextInt(sectionCount + 1).coerceAtMost(sectionCount), "S${counter++}", listOf(counter++)) else list.appendSection("S${counter++}")
                            2 -> if (sectionCount > 0) list.appendItem(rng.nextInt(sectionCount), counter++)
                            3 -> {
                                val si = rng.nextInt(sectionCount)
                                val items = list.value[si].items
                                if (items.size > 0) list.removeItem(si, rng.nextInt(items.size))
                            }
                            4 -> {
                                val si = rng.nextInt(sectionCount)
                                val items = list.value[si].items
                                if (items.size > 0) list.setItem(si, rng.nextInt(items.size), counter++)
                            }
                            5 -> if (sectionCount > 1) list.moveSection(rng.nextInt(sectionCount), rng.nextInt(sectionCount))
                        }
                        advanceUntilIdle()
                    }
                }
                deltas.assertFlatOracle()
            } catch (t: Throwable) {
                throw AssertionError("flatten fuzz trial #$trial failed: ${t.message}")
            }
        }
    }
}
