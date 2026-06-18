@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MutableSectionedDeltaListTest {

    private fun initial() = listOf(
        Section("A", listOf(1, 2)),
        Section("B", listOf(3, 4, 5))
    )

    @Test
    fun allOperationsAreOracleConsistent() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val deltas: List<SectionedDelta<String, Int>> = collectDriven(list) {
            list.appendItem(0, 9); advanceUntilIdle()
            list.insertItem(1, 0, 8); advanceUntilIdle()
            list.removeItem(0, 0); advanceUntilIdle()
            list.setItem(1, 2, 77); advanceUntilIdle()
            list.moveItem(1, 0, 2); advanceUntilIdle()
            list.appendSection("C", listOf(100)); advanceUntilIdle()
            list.insertSection(0, "Z", listOf(0)); advanceUntilIdle()
            list.updateSectionHeader(0, "Z2"); advanceUntilIdle()
            list.moveSection(0, 2); advanceUntilIdle()
            list.removeSection(1); advanceUntilIdle()
            list.updateSection(0) { it.add(0, -1); it.removeAt(it.size - 1) }; advanceUntilIdle()
            list.reload(listOf(Section("Only", listOf(1)))); advanceUntilIdle()
        }

        deltas.assertSectionedOracle()
        assertEquals(listOf("Only"), deltas.last().sections.map { it.header })
        assertEquals(listOf(1), deltas.last().sections[0].items.softLoadedItems())
    }

    @Test
    fun emptyOrNoOpMutationsEmitNothingExtra() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val deltas = collectDriven(list) {
            list.moveItem(0, 1, 1); advanceUntilIdle()      // same index -> no emission
            list.moveSection(0, 0); advanceUntilIdle()       // same index -> no emission
            list.updateSection(1) { /* no change */ }; advanceUntilIdle()
            list.appendItem(0, 42); advanceUntilIdle()       // a real emission
        }

        deltas.assertSectionedOracle()
        // initial reload + exactly one real mutation emission
        assertEquals(2, deltas.size)
        assertEquals(SectionedChange.Items::class, deltas.last().change::class)
    }

    @Test
    fun flattenCrossCheckIsFlatOracleConsistent() = runTest {
        val list = mutableSectionedDeltaListOf(initial())

        val flat = list.flatten(
            header = { h -> "H:$h" },
            item = { i -> "I:$i" }
        )

        val deltas = collectDriven(flat) {
            list.appendItem(0, 9); advanceUntilIdle()
            list.removeItem(1, 0); advanceUntilIdle()
            list.appendSection("C", listOf(100)); advanceUntilIdle()
            list.setItem(0, 0, 55); advanceUntilIdle()
        }

        deltas.assertFlatOracle()
    }
}
