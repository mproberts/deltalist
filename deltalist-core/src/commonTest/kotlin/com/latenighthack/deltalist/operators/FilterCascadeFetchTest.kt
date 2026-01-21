package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Tests for the filter operator's cascade fetch behavior.
 *
 * When filtering a paginated source, accessing a filtered index beyond loaded items
 * should trigger cascading fetches from the source until the requested index is
 * satisfied or the source is exhausted.
 */
class FilterCascadeFetchTest {

    /**
     * Test helper: A SoftList that simulates pagination with controllable loading.
     * Tracks fetch requests and allows staged loading of data.
     */
    private class MockFetchablePaginatedList<T>(
        private var loadedItems: List<T>,
        private val allItems: List<T>,
        private val onFetch: (Int) -> Unit = {}
    ) : AbstractList<T>(), SoftList<T> {

        val fetchRequests = mutableListOf<Int>()

        override val size: Int = allItems.size

        override fun get(index: Int): T {
            if (index < 0) throw IndexOutOfBoundsException("Index $index is negative")
            if (index >= loadedItems.size) {
                fetchRequests.add(index)
                onFetch(index)
                throw IndexOutOfBoundsException("Index $index beyond loaded (${loadedItems.size})")
            }
            return loadedItems[index]
        }

        override fun softGet(index: Int): SoftValue<T>? {
            if (index < 0 || index >= size) return null
            return if (index < loadedItems.size) {
                SoftValue.Present(loadedItems[index])
            } else {
                SoftValue.NotLoaded {
                    fetchRequests.add(index)
                    onFetch(index)
                }
            }
        }

        fun loadMore(count: Int): List<T> {
            val newEnd = minOf(loadedItems.size + count, allItems.size)
            loadedItems = allItems.subList(0, newEnd)
            return loadedItems
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is MockFetchablePaginatedList<*>) return false
            return loadedItems == other.loadedItems && allItems == other.allItems
        }

        override fun hashCode(): Int {
            var result = loadedItems.hashCode()
            result = 31 * result + allItems.hashCode()
            return result
        }
    }

    @Test
    fun cascadeFetch_accessingUnloadedFilteredIndexTriggersFetch() = runTest {
        // Setup: 100 items total, first 10 loaded, 50% pass filter
        val allItems = (1..100).toList()
        val mockList = MockFetchablePaginatedList(
            loadedItems = allItems.take(10),
            allItems = allItems
        )

        val sourceFlow = MutableStateFlow(Delta(mockList, Change.Reload))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Initial state: 5 loaded items (2,4,6,8,10), estimated 50 total
        assertEquals(1, results.size)
        assertEquals(50, results[0].items.size)

        // Access filtered index 10 (beyond loaded 5)
        // This should trigger a fetch on the source
        try {
            results[0].items[10]
        } catch (_: IndexOutOfBoundsException) {}

        delay(50)
        job.cancel()

        // Should have recorded a fetch request
        assertTrue(mockList.fetchRequests.isNotEmpty(), "Expected fetch request when accessing unloaded index")
    }

    @Test
    fun cascadeFetch_continuesFetchingUntilIndexSatisfied() = runTest {
        // Setup: 40 items total, first 10 loaded, 50% pass filter
        // Accessing filtered index 15 requires loading items until index 30+ in source
        val allItems = (1..40).toList()
        var currentLoaded = allItems.take(10).toMutableList()

        val sourceFlow = MutableStateFlow(
            Delta(
                MockFetchablePaginatedList(currentLoaded, allItems),
                Change.Reload
            )
        )
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Initial: 5 even loaded, estimated 20 total
        assertEquals(1, results.size)
        assertEquals(20, results[0].items.size)

        // Access filtered index 15 - needs more data
        try {
            results[0].items[15]
        } catch (_: IndexOutOfBoundsException) {}

        // Simulate loading more data in response
        currentLoaded = allItems.take(20).toMutableList()
        sourceFlow.value = Delta(
            MockFetchablePaginatedList(currentLoaded, allItems),
            Change.Mutations(listOf(Mutation.Insert(10, 10)))
        )

        delay(50)

        // Now have 10 even loaded, still need more for index 15
        // Access again
        try {
            results.last().items[15]
        } catch (_: IndexOutOfBoundsException) {}

        // Load even more
        currentLoaded = allItems.take(32).toMutableList()
        sourceFlow.value = Delta(
            MockFetchablePaginatedList(currentLoaded, allItems),
            Change.Mutations(listOf(Mutation.Insert(20, 12)))
        )

        delay(50)
        job.cancel()

        // Now have 16 even loaded (2,4,6,8,10,12,14,16,18,20,22,24,26,28,30,32)
        // Index 15 should be accessible (value 32)
        val lastDelta = results.last()
        val soft = lastDelta.items.softGetOrNull(15)
        assertIs<SoftValue.Present<Int>>(soft, "Index 15 should be loaded")
        assertEquals(32, soft.value, "Index 15 should be value 32")
    }

    @Test
    fun cascadeFetch_stopsWhenSourceExhausted() = runTest {
        // Setup: 20 items total, filter keeps only multiples of 5
        // Accessing filtered index 10 is impossible (only 4 items pass)
        val allItems = (1..20).toList()
        var currentLoaded = allItems.take(5).toMutableList()

        val sourceFlow = MutableStateFlow(
            Delta(
                MockFetchablePaginatedList(currentLoaded, allItems),
                Change.Reload
            )
        )
        // Only multiples of 5 pass: 5, 10, 15, 20
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 5 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Initial: 1 loaded (5), estimated 4 total (20% * 20)
        assertEquals(1, results.size)
        assertEquals(4, results[0].items.size)

        // Load all remaining data
        currentLoaded = allItems.toMutableList()
        sourceFlow.value = Delta(
            MockFetchablePaginatedList(currentLoaded, allItems),
            Change.Mutations(listOf(Mutation.Insert(5, 15)))
        )

        delay(50)
        job.cancel()

        // All data loaded, only 4 items pass filter
        val lastDelta = results.last()
        assertEquals(4, lastDelta.items.size, "Should have exactly 4 items")

        // All 4 should be present
        repeat(4) { i ->
            assertIs<SoftValue.Present<Int>>(lastDelta.items.softGetOrNull(i), "Index $i should be present")
        }

        // Verify values
        assertEquals(listOf(5, 10, 15, 20), (0 until 4).map {
            (lastDelta.items.softGetOrNull(it) as SoftValue.Present).value
        })
    }

    @Test
    fun cascadeFetch_multiplePendingAccessesTracked() = runTest {
        // Setup: 60 items, first 10 loaded, 50% pass filter
        val allItems = (1..60).toList()
        var currentLoaded = allItems.take(10).toMutableList()

        val sourceFlow = MutableStateFlow(
            Delta(
                MockFetchablePaginatedList(currentLoaded, allItems),
                Change.Reload
            )
        )
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Access multiple indices beyond loaded
        try { results[0].items[10] } catch (_: IndexOutOfBoundsException) {}
        try { results[0].items[15] } catch (_: IndexOutOfBoundsException) {}
        try { results[0].items[20] } catch (_: IndexOutOfBoundsException) {}

        // Load more data
        currentLoaded = allItems.take(30).toMutableList()
        sourceFlow.value = Delta(
            MockFetchablePaginatedList(currentLoaded, allItems),
            Change.Mutations(listOf(Mutation.Insert(10, 20)))
        )

        delay(50)

        // Should have 15 even numbers loaded now
        val delta = results.last()

        // Indices 10, 15 should now be loaded
        assertIs<SoftValue.Present<Int>>(delta.items.softGetOrNull(10), "Index 10 should be loaded")
        assertIs<SoftValue.Present<Int>>(delta.items.softGetOrNull(14), "Index 14 should be loaded")

        // Index 20 still needs more data (need 42 source items for 21 filtered)
        // Continue loading
        currentLoaded = allItems.take(50).toMutableList()
        sourceFlow.value = Delta(
            MockFetchablePaginatedList(currentLoaded, allItems),
            Change.Mutations(listOf(Mutation.Insert(30, 20)))
        )

        delay(50)
        job.cancel()

        // Now all accessed indices should be satisfied
        val lastDelta = results.last()
        assertIs<SoftValue.Present<Int>>(lastDelta.items.softGetOrNull(20), "Index 20 should be loaded")
    }

    @Test
    fun cascadeFetch_predicateChangeClearsPendingAccesses() = runTest {
        // Setup: pending accesses should be cleared when predicate changes
        val allItems = (1..100).toList()
        val currentLoaded = allItems.take(10).toMutableList()

        val sourceFlow = MutableStateFlow(
            Delta(
                MockFetchablePaginatedList(currentLoaded, allItems),
                Change.Reload
            )
        )
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Access an unloaded index
        try { results[0].items[30] } catch (_: IndexOutOfBoundsException) {}

        // Change predicate - should clear pending accesses
        predicateFlow.value = { it % 3 == 0 }

        delay(50)
        job.cancel()

        // The new filtered list should be based on divisible by 3
        val lastDelta = results.last()

        // Check that loaded items are 3, 6, 9 (divisible by 3 from first 10)
        val loadedValues = (0 until lastDelta.items.size)
            .mapNotNull { lastDelta.items.softGetOrNull(it) }
            .filterIsInstance<SoftValue.Present<Int>>()
            .map { it.value }

        assertEquals(listOf(3, 6, 9), loadedValues, "Should have items divisible by 3")
    }

    @Test
    fun cascadeFetch_filterItemsStaticAlsoTriggersFetch() = runTest {
        // Test that the static filterItems also supports cascade fetch via FilteredList.get()
        val allItems = (1..50).toList()
        val mockList = MockFetchablePaginatedList(
            loadedItems = allItems.take(10),
            allItems = allItems
        )

        val sourceFlow = MutableStateFlow(Delta(mockList, Change.Reload))

        val filtered = sourceFlow.filterItems { it % 2 == 0 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)

        // Access filtered index beyond loaded
        try {
            results[0].items[10]
        } catch (_: IndexOutOfBoundsException) {}

        delay(50)
        job.cancel()

        // Should have triggered a fetch (via FilteredList.get())
        assertTrue(mockList.fetchRequests.isNotEmpty(), "Static filterItems should trigger fetch on unloaded access")
    }

    @Test
    fun cascadeFetch_softGetDoesNotTriggerFetch() = runTest {
        // softGet should NOT trigger fetches - only get() should
        val allItems = (1..50).toList()
        val mockList = MockFetchablePaginatedList(
            loadedItems = allItems.take(10),
            allItems = allItems
        )

        val sourceFlow = MutableStateFlow(Delta(mockList, Change.Reload))
        val predicateFlow = MutableStateFlow<(Int) -> Boolean> { it % 2 == 0 }

        val filtered = sourceFlow.filterItemsDynamic(predicateFlow)

        val results = mutableListOf<Delta<Int>>()
        val job = launch {
            filtered.collect { results.add(it) }
        }

        delay(50)
        mockList.fetchRequests.clear()

        // Use softGet to check status without triggering fetch
        val soft = results[0].items.softGetOrNull(20)

        delay(50)
        job.cancel()

        // Should NOT have triggered a fetch
        assertTrue(mockList.fetchRequests.isEmpty(), "softGet should not trigger fetch")

        // But should indicate NotLoaded
        assertTrue(soft is SoftValue.NotLoaded, "softGet should return NotLoaded for unloaded index")
    }
}

// Extension for test readability
private fun <T> List<T>.softGetOrNull(index: Int): SoftValue<T>? {
    return if (this is SoftList<T>) {
        softGet(index)
    } else {
        if (index < 0 || index >= size) null
        else SoftValue.Present(get(index))
    }
}
