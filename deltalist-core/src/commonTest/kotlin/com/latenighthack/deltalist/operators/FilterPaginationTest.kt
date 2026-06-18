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
import com.latenighthack.deltalist.AbstractSoftList

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for filter operator behavior with paginated (SoftList) sources.
 * These tests verify that placeholder estimation and mutations work correctly
 * when the filter ratio changes over time.
 */
class FilterPaginationTest {

    /**
     * Test helper: A SoftList that simulates pagination with estimated size.
     * Includes custom equals/hashCode to avoid iterating through unloaded items
     * during StateFlow equality checks.
     */
    private class MockPaginatedList<T>(
        private val loadedItems: List<T>,
        private val estimatedTotal: Int
    ) : AbstractSoftList<T>() {

        override val size: Int = maxOf(loadedItems.size, estimatedTotal)

        override fun softGet(index: Int): SoftValue<T>? {
            if (index < 0 || index >= size) return null
            return if (index < loadedItems.size) {
                SoftValue.Present(loadedItems[index])
            } else {
                SoftValue.NotLoaded()
            }
        }

        // Custom equals/hashCode to avoid iterating through unloaded items
        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is MockPaginatedList<*>) return false
            return loadedItems == other.loadedItems && estimatedTotal == other.estimatedTotal
        }

        override fun hashCode(): Int {
            var result = loadedItems.hashCode()
            result = 31 * result + estimatedTotal
            return result
        }
    }

    @Test
    fun filterPaginatedList_initialEstimation() = runTest {
        // Source: 10 loaded items, estimated 100 total
        // 5 of 10 pass filter (50% ratio)
        // Expected: 5 loaded + estimated 45 placeholders = 50 total
        val source = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = source.filterItems { it % 2 == 0 } // Keep even numbers

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        // Wait for collection
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(1, results.size)
        val delta = results[0]

        // Should have 5 loaded items (2, 4, 6, 8, 10)
        assertEquals(5, (0 until delta.items.size).count {
            delta.items.softGetOrNull(it) is SoftValue.Present
        })

        // Estimated size should be ~50 (100 * 50% ratio)
        assertEquals(50, delta.items.size)
    }

    @Test
    fun filterPaginatedList_loadMoreData_ratioStable() = runTest {
        // Initial: 10 loaded, 5 pass (50%)
        // After load: 20 loaded, 10 pass (50% - stable)
        // Mutations should convert Insert to Update for positions that were placeholders

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)

        // Load more data with same ratio
        sourceFlow.value = Delta(
            MockPaginatedList(
                loadedItems = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
                estimatedTotal = 100
            ),
            Change.Mutations(listOf(Mutation.Insert(10, 10)))
        )

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val secondDelta = results[1]
        // Now have 10 loaded items passing filter
        assertEquals(10, (0 until secondDelta.items.size).count {
            secondDelta.items.softGetOrNull(it) is SoftValue.Present
        })

        // Estimated size should still be ~50
        assertEquals(50, secondDelta.items.size)

        // The mutation should be Update (not Insert) since positions 5-9 were placeholders
        val change = secondDelta.change
        assertIs<Change.Mutations>(change)

        // Should have Update mutation for the 5 new items at positions 5-9
        val hasUpdate = change.operations.any { it is Mutation.Update }
        assertTrue(hasUpdate, "Expected Update mutation for filling placeholders, got: ${change.operations}")
    }

    @Test
    fun filterPaginatedList_ratioShrinks_removePlaceholders() = runTest {
        // Initial: 10 loaded, 5 pass (50%) → estimated 50
        // After load: 20 loaded, 6 pass (30%) → estimated 30
        // Should emit Remove for excess placeholders (positions 30-49)

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(2, 4, 6, 8, 10, 1, 3, 5, 7, 9), // 5 even, 5 odd
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)

        val initialSize = results[0].items.size
        assertEquals(50, initialSize, "Initial estimated size should be 50 (50% of 100)")

        // Load more data where ratio drops significantly (only 1 of 10 new items passes)
        sourceFlow.value = Delta(
            MockPaginatedList(
                // First 10: 5 even (2,4,6,8,10), 5 odd (1,3,5,7,9)
                // Next 10: 1 even (12), 9 odd (11,13,15,17,19,21,23,25,27)
                loadedItems = listOf(2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 12, 11, 13, 15, 17, 19, 21, 23, 25, 27),
                estimatedTotal = 100
            ),
            Change.Mutations(listOf(Mutation.Insert(10, 10)))
        )

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val secondDelta = results[1]
        // New ratio: 6/20 = 30%, estimated = 30
        val newEstimatedSize = secondDelta.items.size
        assertEquals(30, newEstimatedSize, "New estimated size should be 30 (30% of 100)")

        // Should have 6 loaded items
        assertEquals(6, (0 until secondDelta.items.size).count {
            secondDelta.items.softGetOrNull(it) is SoftValue.Present
        })

        // Verify mutations: should have Remove for excess placeholders (50 -> 30 = 20 removed)
        val change = secondDelta.change
        assertIs<Change.Mutations>(change)

        // Should have Update for the new item at position 5 and Remove for excess placeholders
        val removes = change.operations.filterIsInstance<Mutation.Remove>()
        val totalRemoved = removes.sumOf { it.count }
        assertEquals(20, totalRemoved, "Should remove 20 excess placeholders (50 - 30), got: ${change.operations}")
    }

    @Test
    fun filterPaginatedList_ratioGrows_addPlaceholders() = runTest {
        // Initial: 10 loaded, 1 pass (10%) → estimated 10
        // After load: 20 loaded, 10 pass (50%) → estimated 50
        // Should have Insert mutations for new placeholders

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    // Only 2 is even out of first 10
                    loadedItems = listOf(2, 1, 3, 5, 7, 9, 11, 13, 15, 17),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)

        val initialSize = results[0].items.size
        assertEquals(10, initialSize, "Initial estimated size should be 10 (10% of 100)")

        // Load more data where many pass the filter
        sourceFlow.value = Delta(
            MockPaginatedList(
                // First 10: 1 even (2)
                // Next 10: 9 even (4,6,8,10,12,14,16,18,20)
                loadedItems = listOf(2, 1, 3, 5, 7, 9, 11, 13, 15, 17, 4, 6, 8, 10, 12, 14, 16, 18, 20, 19),
                estimatedTotal = 100
            ),
            Change.Mutations(listOf(Mutation.Insert(10, 10)))
        )

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val secondDelta = results[1]
        // New ratio: 10/20 = 50%, estimated = 50
        val newEstimatedSize = secondDelta.items.size
        assertEquals(50, newEstimatedSize, "New estimated size should be 50 (50% of 100)")

        // Should have 10 loaded items
        assertEquals(10, (0 until secondDelta.items.size).count {
            secondDelta.items.softGetOrNull(it) is SoftValue.Present
        })

        // Verify mutations: should have Update for positions 1-9 (were placeholders, now real items)
        // and Insert for positions 10-49 (new placeholders)
        val change = secondDelta.change
        assertIs<Change.Mutations>(change)

        // Should have Insert for new placeholders (10 - 50 = 40 new positions)
        val inserts = change.operations.filterIsInstance<Mutation.Insert>()
        val totalInserted = inserts.sumOf { it.count }
        assertEquals(40, totalInserted, "Should insert 40 new placeholders (50 - 10), got: ${change.operations}")
    }

    @Test
    fun filterPaginatedList_zeroPassInitially() = runTest {
        // Initial: 10 loaded, 0 pass, but the source still has more pages (10 < 100), so
        // the filter must keep one NotLoaded placeholder instead of collapsing to size 0.
        // A size-0 list renders empty and unscrollable, permanently stalling pagination.

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19), // All odd
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 } // Keep even

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(1, results.size)
        // Anti-stall: at least one placeholder while the source is not exhausted.
        assertEquals(1, results[0].items.size)
        assertIs<SoftValue.NotLoaded>(results[0].items.softGetOrNull(0))
    }

    @Test
    fun filterPaginatedList_allPassInitially() = runTest {
        // Initial: 10 loaded, all 10 pass → 100% ratio → estimated 100

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20), // All even
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(1, results.size)
        // 100% ratio → 100 estimated size
        assertEquals(100, results[0].items.size)

        // 10 loaded
        assertEquals(10, (0 until results[0].items.size).count {
            results[0].items.softGetOrNull(it) is SoftValue.Present
        })
    }

    @Test
    fun filterPaginatedList_insertAtPlaceholderPosition_becomesUpdate() = runTest {
        // This test specifically verifies that Insert mutations at placeholder
        // positions are converted to Update mutations

        val sourceFlow = MutableStateFlow(
            Delta(
                MockPaginatedList(
                    loadedItems = listOf(2, 4, 1, 3), // 2 even, 2 odd = 50%
                    estimatedTotal = 20
                ),
                Change.Reload
            )
        )

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        kotlinx.coroutines.delay(50)

        // Initial: 2 loaded, estimated 10 (50% of 20)
        assertEquals(10, results[0].items.size)

        // Load 4 more items, 2 pass filter
        sourceFlow.value = Delta(
            MockPaginatedList(
                loadedItems = listOf(2, 4, 1, 3, 6, 8, 5, 7), // 4 even, 4 odd = 50%
                estimatedTotal = 20
            ),
            Change.Mutations(listOf(Mutation.Insert(4, 4)))
        )

        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(2, results.size)

        val change = results[1].change
        assertIs<Change.Mutations>(change)

        // The 2 new filtered items at positions 2 and 3 should be Updates
        // because those positions were placeholders before
        val updates = change.operations.filterIsInstance<Mutation.Update>()
        assertTrue(updates.isNotEmpty(), "Expected Update mutations, got: ${change.operations}")

        // Should NOT have Insert at positions that were placeholders
        val inserts = change.operations.filterIsInstance<Mutation.Insert>()
        inserts.forEach { insert ->
            assertTrue(
                insert.index >= 10, // Should only insert beyond old estimated size
                "Insert at ${insert.index} should have been Update (was within estimated size 10)"
            )
        }
    }
}
