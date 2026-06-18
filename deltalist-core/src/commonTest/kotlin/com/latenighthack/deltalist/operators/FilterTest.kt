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
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterTest {

    @Test
    fun filterInitialReload() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        // Wait for initial emission
        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(2, 4), results[0].items.toList())
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun filterInsertPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 3, 5))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.insert(1, 2) // Insert even number

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        // Second emission should have the inserted item
        assertEquals(listOf(2), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
        assertEquals(1, insert.count)
    }

    @Test
    fun filterInsertNonPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(2, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.insert(1, 3) // Insert odd number - should not appear in filtered

        delay(50)
        job.cancel()

        // Should only have initial reload, no second emission for non-passing insert
        assertEquals(1, results.size)
    }

    @Test
    fun filterRemovePassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.removeAt(1) // Remove 2 (even)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(1, remove.count)
    }

    @Test
    fun filterRemoveNonPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.removeAt(0) // Remove 1 (odd) - not in filtered list

        delay(50)
        job.cancel()

        // Should only have initial reload, no emission for removing non-passing item
        assertEquals(1, results.size)
    }

    @Test
    fun filterUpdateItemStillPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(1, 6) // Change 2 to 6 (still even)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 4), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val update = change.operations[0] as Mutation.Update
        assertEquals(0, update.index)
    }

    @Test
    fun filterUpdateItemNowFails() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(1, 5) // Change 2 to 5 (now odd - should be removed)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
    }

    @Test
    fun filterUpdateItemNowPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.set(0, 6) // Change 1 to 6 (now even - should be inserted)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 2, 4), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        assertEquals(1, change.operations.size)
        val insert = change.operations[0] as Mutation.Insert
        assertEquals(0, insert.index)
    }

    @Test
    fun filterBatchInsertMixed() = runTest {
        val source = mutableDeltaListOf(listOf(2, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        source.update { list ->
            list.add(0, 1) // odd
            list.add(1, 6) // even
            list.add(2, 3) // odd
            list.add(3, 8) // even
        }

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 8, 2, 4), results[1].items.toList())
    }

    // Move mutation tests

    @Test
    fun filterMovePassingItemForward() = runTest {
        // Source: [1, 2, 3, 4, 5, 6] -> Filtered: [2, 4, 6]
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4, 6), results.last().items.toList())

        // Move 2 from index 1 to index 4 (after 4, before 5)
        // Source becomes: [1, 3, 4, 5, 2, 6]
        // Filtered should be: [4, 2, 6]
        source.move(1, 4)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(4, 2, 6), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        // Should have a Move mutation
        val move = change.operations.find { it is Mutation.Move } as? Mutation.Move
        assertTrue(move != null, "Expected Move mutation, got: ${change.operations}")
        assertEquals(0, move.fromIndex) // 2 was at filtered index 0
        assertEquals(1, move.toIndex)   // moved to filtered index 1
    }

    @Test
    fun filterMovePassingItemBackward() = runTest {
        // Source: [1, 2, 3, 4, 5, 6] -> Filtered: [2, 4, 6]
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4, 6), results.last().items.toList())

        // Move 6 from index 5 to index 0
        // Source becomes: [6, 1, 2, 3, 4, 5]
        // Filtered should be: [6, 2, 4]
        source.move(5, 0)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(listOf(6, 2, 4), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        val move = change.operations.find { it is Mutation.Move } as? Mutation.Move
        assertTrue(move != null, "Expected Move mutation, got: ${change.operations}")
        assertEquals(2, move.fromIndex) // 6 was at filtered index 2
        assertEquals(0, move.toIndex)   // moved to filtered index 0
    }

    @Test
    fun filterMoveNonPassingItem() = runTest {
        // Source: [1, 2, 3, 4] -> Filtered: [2, 4]
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4), results.last().items.toList())

        // Move 1 (odd, not in filter) from index 0 to index 3
        // Source becomes: [2, 3, 4, 1]
        // Filtered should still be: [2, 4] (no change)
        source.move(0, 3)

        delay(50)
        job.cancel()

        // No new emission since the moved item doesn't pass filter
        assertEquals(1, results.size)
    }

    @Test
    fun filterMovePassingItemToSameFilteredPosition() = runTest {
        // Source: [1, 2, 3, 4, 5, 6] -> Filtered: [2, 4, 6]
        val source = mutableDeltaListOf(listOf(1, 2, 3, 4, 5, 6))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2, 4, 6), results.last().items.toList())

        // Move 2 from index 1 to index 2 (between 3 and 4, but still before 4)
        // Source becomes: [1, 3, 2, 4, 5, 6]
        // Filtered should still be: [2, 4, 6] (same order)
        source.move(1, 2)

        delay(50)
        job.cancel()

        // The filtered list order didn't change, so no emission
        assertEquals(1, results.size)
    }

    // Edge case tests

    @Test
    fun filterEmptySource() = runTest {
        val source = mutableDeltaListOf<Int>(emptyList())
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(emptyList(), results[0].items.toList())
        assertTrue(results[0].change is Change.Reload)
    }

    @Test
    fun filterNothingPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 3, 5, 7, 9))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(emptyList(), results[0].items.toList())
    }

    @Test
    fun filterEverythingPasses() = runTest {
        val source = mutableDeltaListOf(listOf(2, 4, 6, 8, 10))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(2, 4, 6, 8, 10), results[0].items.toList())
    }

    @Test
    fun filterSingleItemPasses() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3))
        val filtered = source.filterItems { it == 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        assertEquals(listOf(2), results[0].items.toList())
    }

    @Test
    fun filterRemoveLastPassingItem() = runTest {
        val source = mutableDeltaListOf(listOf(1, 2, 3))
        val filtered = source.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        assertEquals(listOf(2), results.last().items.toList())

        // Remove the only passing item
        source.removeAt(1)

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertEquals(emptyList(), results[1].items.toList())
        val change = results[1].change as Change.Mutations
        val remove = change.operations[0] as Mutation.Remove
        assertEquals(0, remove.index)
        assertEquals(1, remove.count)
    }
}
