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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Map and LazyMap operators' SoftList passthrough behavior.
 *
 * When the source is a SoftList, the mapped lists should:
 * 1. Propagate the source's size (including estimated size)
 * 2. Return SoftValue.Present with transformed value for loaded items
 * 3. Return SoftValue.NotLoaded for unloaded items (passthrough from source)
 * 4. Trigger fetches when get() is called on unloaded items
 */
class MapSoftListTest {

    /**
     * Test helper: A SoftList that simulates pagination.
     */
    private class MockSoftList<T>(
        private val loadedItems: List<T>,
        private val estimatedTotal: Int,
        private val onFetch: (Int) -> Unit = {}
    ) : AbstractList<T>(), SoftList<T> {

        val fetchRequests = mutableListOf<Int>()

        override val size: Int = estimatedTotal

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

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is MockSoftList<*>) return false
            return loadedItems == other.loadedItems && estimatedTotal == other.estimatedTotal
        }

        override fun hashCode(): Int {
            var result = loadedItems.hashCode()
            result = 31 * result + estimatedTotal
            return result
        }
    }

    // ==================== mapItems Tests ====================

    @Test
    fun mapItems_preservesSoftListSize() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(1, results.size)
        // Size should be the estimated total, not just loaded items
        assertEquals(100, results[0].items.size)
    }

    @Test
    fun mapItems_softGetReturnsTransformedPresent() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Check loaded items return Present with transformed value
        val soft0 = results[0].items.softGetOrNull(0)
        assertIs<SoftValue.Present<Int>>(soft0)
        assertEquals(2, soft0.value) // 1 * 2 = 2

        val soft4 = results[0].items.softGetOrNull(4)
        assertIs<SoftValue.Present<Int>>(soft4)
        assertEquals(10, soft4.value) // 5 * 2 = 10
    }

    @Test
    fun mapItems_softGetReturnsNotLoadedForUnloadedItems() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Unloaded items should return NotLoaded
        val soft10 = results[0].items.softGetOrNull(10)
        assertTrue((soft10 is SoftValue.NotLoaded))

        val soft50 = results[0].items.softGetOrNull(50)
        assertTrue((soft50 is SoftValue.NotLoaded))
    }

    @Test
    fun mapItems_softGetReturnsNullForOutOfBounds() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 10
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Out of bounds should return null
        assertNull(results[0].items.softGetOrNull(-1))
        assertNull(results[0].items.softGetOrNull(10))
        assertNull(results[0].items.softGetOrNull(100))
    }

    @Test
    fun mapItems_getOnUnloadedTriggersFetch() = runTest {
        val mockList = MockSoftList(
            loadedItems = listOf(1, 2, 3),
            estimatedTotal = 100
        )
        val sourceFlow = MutableStateFlow(Delta(mockList, Change.Reload))

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)

        // Access unloaded item via get() - should throw but trigger fetch
        try {
            results[0].items[50]
        } catch (_: IndexOutOfBoundsException) {}

        delay(50)
        job.cancel()

        // Should have recorded a fetch request
        assertTrue(mockList.fetchRequests.contains(50), "get() should trigger fetch on source")
    }

    @Test
    fun mapItems_withRegularListNoSoftBehavior() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(listOf(1, 2, 3, 4, 5), Change.Reload)
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Regular list - all items should be Present
        assertEquals(5, results[0].items.size)
        for (i in 0 until 5) {
            val soft = results[0].items.softGetOrNull(i)
            assertIs<SoftValue.Present<Int>>(soft)
            assertEquals((i + 1) * 2, soft.value)
        }
    }

    // ==================== lazyMap Tests ====================

    @Test
    fun lazyMap_preservesSoftListSize() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMap { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(100, results[0].items.size)
    }

    @Test
    fun lazyMap_softGetReturnsTransformedPresent() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 50
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMap { "value:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val soft0 = results[0].items.softGetOrNull(0)
        assertIs<SoftValue.Present<String>>(soft0)
        assertEquals("value:1", soft0.value)

        val soft2 = results[0].items.softGetOrNull(2)
        assertIs<SoftValue.Present<String>>(soft2)
        assertEquals("value:3", soft2.value)
    }

    @Test
    fun lazyMap_softGetReturnsNotLoadedForUnloadedItems() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMap { "value:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val soft50 = results[0].items.softGetOrNull(50)
        assertTrue((soft50 is SoftValue.NotLoaded))
    }

    // ==================== lazyMapWithAccess Tests ====================

    @Test
    fun lazyMapWithAccess_preservesSoftListSize() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMapWithAccess { it * 2 }

        val results = mutableListOf<Delta<com.latenighthack.deltalist.LazyAccess<Int>>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(100, results[0].items.size)
    }

    @Test
    fun lazyMapWithAccess_softGetReturnsPresentForLoadedItems() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 50
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMapWithAccess { it * 2 }

        val results = mutableListOf<Delta<com.latenighthack.deltalist.LazyAccess<Int>>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Loaded items should return Present(LazyAccess)
        val soft0 = results[0].items.softGetOrNull(0)
        assertIs<SoftValue.Present<*>>(soft0)

        val soft2 = results[0].items.softGetOrNull(2)
        assertIs<SoftValue.Present<*>>(soft2)
    }

    @Test
    fun lazyMapWithAccess_softGetReturnsNotLoadedForUnloadedItems() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMapWithAccess { it * 2 }

        val results = mutableListOf<Delta<com.latenighthack.deltalist.LazyAccess<Int>>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val soft50 = results[0].items.softGetOrNull(50)
        assertTrue((soft50 is SoftValue.NotLoaded))
    }

    @Test
    fun lazyMapWithAccess_acquireOnLoadedItemWorks() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 50
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.lazyMapWithAccess { it * 10 }

        val results = mutableListOf<Delta<com.latenighthack.deltalist.LazyAccess<Int>>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Get the LazyAccess for a loaded item and acquire it
        val access = results[0].items[2] // Source value 3
        assertEquals(30, access.getOrAcquire()) // 3 * 10 = 30
        assertTrue(access.isAcquired)
    }

    // ==================== Mutation Passthrough Tests ====================

    @Test
    fun mapItems_mutationsPassThrough() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 50
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)

        // Emit update with Insert mutation
        sourceFlow.value = Delta(
            MockSoftList(
                loadedItems = listOf(1, 2, 3, 4, 5),
                estimatedTotal = 50
            ),
            Change.Mutations(listOf(Mutation.Insert(3, 2)))
        )

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        val change = results[1].change
        assertIs<Change.Mutations>(change)
        assertEquals(1, change.operations.size)
        val insert = change.operations[0]
        assertIs<Mutation.Insert>(insert)
        assertEquals(3, insert.index)
        assertEquals(2, insert.count)
    }

    @Test
    fun mapItems_reloadPassesThrough() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3),
                    estimatedTotal = 50
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow.mapItems { it * 2 }

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)

        // Emit reload
        sourceFlow.value = Delta(
            MockSoftList(
                loadedItems = listOf(10, 20, 30),
                estimatedTotal = 100
            ),
            Change.Reload
        )

        delay(50)
        job.cancel()

        assertEquals(2, results.size)
        assertIs<Change.Reload>(results[1].change)
        assertEquals(100, results[1].items.size)
    }

    // ==================== Chain Tests ====================

    @Test
    fun mapItems_chainingPreservesSoftBehavior() = runTest {
        val sourceFlow = MutableStateFlow(
            Delta(
                MockSoftList(
                    loadedItems = listOf(1, 2, 3, 4, 5),
                    estimatedTotal = 100
                ),
                Change.Reload
            )
        )

        val mapped = sourceFlow
            .mapItems { it * 2 }      // 2, 4, 6, 8, 10
            .mapItems { it + 1 }      // 3, 5, 7, 9, 11

        val results = mutableListOf<Delta<Int>>()
        val job = launch { mapped.collect { results.add(it) } }

        delay(50)
        job.cancel()

        // Size preserved through chain
        assertEquals(100, results[0].items.size)

        // Loaded items transformed through chain
        val soft0 = results[0].items.softGetOrNull(0)
        assertIs<SoftValue.Present<Int>>(soft0)
        assertEquals(3, soft0.value) // (1 * 2) + 1 = 3

        val soft4 = results[0].items.softGetOrNull(4)
        assertIs<SoftValue.Present<Int>>(soft4)
        assertEquals(11, soft4.value) // (5 * 2) + 1 = 11

        // Unloaded items still NotLoaded
        val soft50 = results[0].items.softGetOrNull(50)
        assertTrue((soft50 is SoftValue.NotLoaded))
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
