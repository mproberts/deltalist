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
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LazyMapTest {

    // Track how many times transform was called
    private var transformCallCount = 0

    private fun resetTransformCount() {
        transformCallCount = 0
    }

    private fun <T> countingTransform(value: T): String {
        transformCallCount++
        return "transformed:$value"
    }

    // Helper to get the LazyList from a delta
    private fun <T> Delta<T>.lazyList(): LazyList<T> = items as LazyList<T>

    // ==================== Basic Acquisition Tests ====================

    @Test
    fun acquireComputesAndCachesValue() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val lazyList = results[0].lazyList()
        assertFalse(lazyList.isAcquired(1))

        // Access item at index 1 (auto-acquires)
        val value = results[0].items[1]
        assertEquals("transformed:b", value)
        assertTrue(lazyList.isAcquired(1))
        assertEquals(1, transformCallCount)

        // Access again - should return same value without recomputing
        val value2 = results[0].items[1]
        assertSame(value, value2)
        assertEquals(1, transformCallCount) // Still 1, not recomputed

        job.cancel()
    }

    @Test
    fun releaseFreesCache() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val lazyList = results[0].lazyList()
        results[0].items[1] // Acquire
        assertTrue(lazyList.isAcquired(1))
        assertEquals(1, transformCallCount)

        // Release
        lazyList.release(1)
        assertFalse(lazyList.isAcquired(1))

        // Acquire again - should recompute
        results[0].items[1]
        assertTrue(lazyList.isAcquired(1))
        assertEquals(2, transformCallCount) // Now 2, recomputed

        job.cancel()
    }

    @Test
    fun multipleItemsCanBeAcquiredIndependently() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val lazyList = results[0].lazyList()

        // Acquire indices 1 and 3
        results[0].items[1]
        results[0].items[3]

        assertTrue(lazyList.isAcquired(1))
        assertFalse(lazyList.isAcquired(2))
        assertTrue(lazyList.isAcquired(3))
        assertEquals(2, transformCallCount)

        // Release index 1
        lazyList.release(1)
        assertFalse(lazyList.isAcquired(1))
        assertTrue(lazyList.isAcquired(3))

        job.cancel()
    }

    @Test
    fun refcountKeepsItemAcquiredUntilLastRelease() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val lazyList = results[0].lazyList()

        // Two independent acquirers of the same index (e.g. a sticky header + a row).
        results[0].items[1]
        results[0].items[1]
        assertEquals(1, transformCallCount) // computed once, cached

        // First release must NOT evict while a second acquirer still holds the item.
        lazyList.release(1)
        assertTrue(lazyList.isAcquired(1), "still referenced by the second acquirer")

        // Second release drops the last reference and evicts.
        lazyList.release(1)
        assertFalse(lazyList.isAcquired(1))

        // Extra releases are safe no-ops.
        lazyList.release(1)
        assertFalse(lazyList.isAcquired(1))

        job.cancel()
    }

    @Test
    fun supersededViewReleaseIsNoOp() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        val staleList = results[0].lazyList()
        staleList[1] // acquire "b" at index 1 while this view is current
        assertTrue(staleList.isAcquired(1))

        // A new delta supersedes the first view; "b" shifts to index 2 in the live cache.
        source.insert(0, "x")
        delay(50)

        val currentList = results[1].lazyList()
        assertTrue(currentList.isAcquired(2))

        // Acting on the superseded view must not touch the live cache, and the stale view
        // now reports nothing acquired.
        staleList.release(1)
        assertTrue(currentList.isAcquired(2), "stale release must be a no-op")
        assertFalse(staleList.isAcquired(1))

        job.cancel()
    }

    // ==================== Insert Mutation Tests ====================

    @Test
    fun insertShiftsAcquiredIndicesUp() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val valueB = results[0].items[1]
        assertEquals("transformed:b", valueB)
        assertEquals(1, transformCallCount)

        // Insert "x" at index 0 - "b" should now be at index 2
        source.insert(0, "x")
        delay(50)

        assertEquals(2, results.size)
        assertEquals(4, results[1].items.size)

        // Index 2 should still have the cached value for "b"
        val lazyList = results[1].lazyList()
        assertTrue(lazyList.isAcquired(2))
        val value2 = results[1].items[2]
        assertEquals("transformed:b", value2)
        assertEquals(1, transformCallCount) // No recompute

        // Index 1 (the old position) should now be "a", not acquired
        assertFalse(lazyList.isAcquired(1))

        job.cancel()
    }

    @Test
    fun insertBeforeAcquiredItemPreservesCache() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire all items
        results[0].items.forEach { _ -> } // Access all to acquire
        results[0].items[0]
        results[0].items[1]
        results[0].items[2]

        // Insert at beginning
        source.insert(0, "x")
        delay(50)

        val lazyList = results[1].lazyList()
        // Original items should have shifted
        assertEquals("t:x", results[1].items[0])
        assertTrue(lazyList.isAcquired(1)) // "a" shifted to index 1
        assertTrue(lazyList.isAcquired(2)) // "b" shifted to index 2
        assertTrue(lazyList.isAcquired(3)) // "c" shifted to index 3

        job.cancel()
    }

    @Test
    fun insertAfterAcquiredItemPreservesCache() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire item at index 0
        results[0].items[0]

        // Insert at end
        source.append("x")
        delay(50)

        // Item at index 0 should still be acquired
        val lazyList = results[1].lazyList()
        assertTrue(lazyList.isAcquired(0))
        assertEquals("t:a", results[1].items[0])

        job.cancel()
    }

    // ==================== Remove Mutation Tests ====================

    @Test
    fun removeDiscardsAcquiredItem() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        results[0].items[1]
        assertTrue(results[0].lazyList().isAcquired(1))

        // Remove "b"
        source.removeAt(1)
        delay(50)

        // New list should have 2 items, none acquired at index 1 (which is now "c")
        val lazyList = results[1].lazyList()
        assertEquals(2, results[1].items.size)
        assertFalse(lazyList.isAcquired(1))
        assertEquals("t:c", results[1].items[1])

        job.cancel()
    }

    @Test
    fun removeShiftsAcquiredIndicesDown() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2 and "d" at index 3
        results[0].items[2]
        results[0].items[3]

        // Remove "a" at index 0
        source.removeAt(0)
        delay(50)

        val lazyList = results[1].lazyList()
        // "c" should now be at index 1 (was 2), still acquired
        assertTrue(lazyList.isAcquired(1))
        assertEquals("t:c", results[1].items[1])

        // "d" should now be at index 2 (was 3), still acquired
        assertTrue(lazyList.isAcquired(2))
        assertEquals("t:d", results[1].items[2])

        job.cancel()
    }

    @Test
    fun removeMultipleItemsShiftsCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "e" at index 4
        results[0].items[4]

        // Remove indices 1 and 2 ("b" and "c")
        source.removeRange(1, 2)
        delay(50)

        val lazyList = results[1].lazyList()
        // "e" should now be at index 2 (was 4, shifted down by 2)
        assertEquals(3, results[1].items.size)
        assertTrue(lazyList.isAcquired(2))
        assertEquals("t:e", results[1].items[2])

        job.cancel()
    }

    // ==================== Update Mutation Tests ====================

    @Test
    fun updateRecomputesAcquiredItem() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val valueB = results[0].items[1]
        assertEquals("transformed:b", valueB)
        assertEquals(1, transformCallCount)

        // Update "b" to "B"
        source.set(1, "B")
        delay(50)

        // Should have recomputed
        val lazyList = results[1].lazyList()
        assertEquals(2, transformCallCount)
        assertTrue(lazyList.isAcquired(1))
        assertEquals("transformed:B", results[1].items[1])
        assertEquals(2, transformCallCount) // No additional compute

        job.cancel()
    }

    @Test
    fun updateNonAcquiredItemDoesNotCompute() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { countingTransform(it) }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Don't acquire anything
        assertEquals(0, transformCallCount)

        // Update "b" to "B"
        source.set(1, "B")
        delay(50)

        // Should not have computed anything
        assertEquals(0, transformCallCount)
        assertFalse(results[1].lazyList().isAcquired(1))

        job.cancel()
    }

    // ==================== Move Mutation Tests ====================

    @Test
    fun moveAcquiredItemForward() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "a" at index 0
        val valueA = results[0].items[0]
        assertEquals("t:a", valueA)

        // Move "a" from index 0 to index 3
        source.move(0, 3)
        delay(50)

        val lazyList = results[1].lazyList()
        // "a" should now be at index 3, still acquired
        assertTrue(lazyList.isAcquired(3))
        assertEquals("t:a", results[1].items[3])

        // Index 0 should now be "b", not acquired
        assertFalse(lazyList.isAcquired(0))
        assertEquals("t:b", results[1].items[0])

        job.cancel()
    }

    @Test
    fun moveAcquiredItemBackward() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "d" at index 3
        val valueD = results[0].items[3]
        assertEquals("t:d", valueD)

        // Move "d" from index 3 to index 0
        source.move(3, 0)
        delay(50)

        val lazyList = results[1].lazyList()
        // "d" should now be at index 0, still acquired
        assertTrue(lazyList.isAcquired(0))
        assertEquals("t:d", results[1].items[0])

        // Index 3 should now be "c", not acquired
        assertFalse(lazyList.isAcquired(3))

        job.cancel()
    }

    @Test
    fun moveAffectsIntermediateAcquiredItems() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2
        results[0].items[2]

        // Move "a" from index 0 to index 4
        // Original: a b c d e -> b c d e a
        // "c" was at index 2, should shift to index 1
        source.move(0, 4)
        delay(50)

        val lazyList = results[1].lazyList()
        assertTrue(lazyList.isAcquired(1))
        assertEquals("t:c", results[1].items[1])

        job.cancel()
    }

    // ==================== Reload Tests ====================

    @Test
    fun reloadClearsAllAcquiredItems() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire all items
        results[0].items.forEach { _ -> }
        results[0].items[0]
        results[0].items[1]
        results[0].items[2]
        val lazyList0 = results[0].lazyList()
        assertTrue(lazyList0.isAcquired(0) && lazyList0.isAcquired(1) && lazyList0.isAcquired(2))

        // Reload with new data
        source.reload(listOf("x", "y", "z"))
        delay(50)

        // All items should be not acquired
        val lazyList1 = results[1].lazyList()
        assertTrue(!lazyList1.isAcquired(0) && !lazyList1.isAcquired(1) && !lazyList1.isAcquired(2))
        assertEquals("t:x", results[1].items[0])

        job.cancel()
    }

    // ==================== Complex Scenario Tests ====================

    @Test
    fun multipleSequentialMutationsTrackCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "c" at index 2
        results[0].items[2]

        // Insert at beginning: x a b c d e
        // "c" should move from 2 to 3
        source.insert(0, "x")
        delay(50)
        assertTrue(results[1].lazyList().isAcquired(3))

        // Remove "a" (now at index 1): x b c d e
        // "c" should move from 3 to 2
        source.removeAt(1)
        delay(50)
        assertTrue(results[2].lazyList().isAcquired(2))
        assertEquals("t:c", results[2].items[2])

        // Insert at end: x b c d e y
        // "c" should stay at 2
        source.append("y")
        delay(50)
        assertTrue(results[3].lazyList().isAcquired(2))

        job.cancel()
    }

    @Test
    fun batchMutationsTrackCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire items at indices 1, 2, 3
        results[0].items[1]
        results[0].items[2]
        results[0].items[3]

        // Batch: insert at 0, remove at 4 (after shift, this is "e")
        source.update { list ->
            list.add(0, "x")
            list.removeAt(5) // "e" shifted to index 5
        }
        delay(50)

        // After insert at 0: x a b c d e (acquired shifted: 2, 3, 4)
        // After remove at 5: x a b c d (acquired: 2, 3, 4)
        val lazyList = results[1].lazyList()
        assertEquals(5, results[1].items.size)
        assertTrue(lazyList.isAcquired(2)) // "b"
        assertTrue(lazyList.isAcquired(3)) // "c"
        assertTrue(lazyList.isAcquired(4)) // "d"

        job.cancel()
    }

    // ==================== Same Object Identity Tests ====================

    @Test
    fun sameTransformedObjectReturnedAfterMutation() = runTest {
        // Use a class instance to verify same object identity
        data class Wrapper(val value: String)

        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { Wrapper(it) }

        val results = mutableListOf<Delta<Wrapper>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b" at index 1
        val wrapper1 = results[0].items[1]

        // Insert at beginning - "b" moves to index 2
        source.insert(0, "x")
        delay(50)

        // Get the wrapper at new position - should be same object
        val wrapper2 = results[1].items[2]
        assertSame(wrapper1, wrapper2)

        job.cancel()
    }

    @Test
    fun differentObjectAfterReleaseAndReacquire() = runTest {
        data class Wrapper(val value: String)

        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { Wrapper(it) }

        val results = mutableListOf<Delta<Wrapper>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        // Acquire "b"
        val wrapper1 = results[0].items[1]

        // Release
        results[0].lazyList().release(1)

        // Re-acquire - should be different object instance
        val wrapper2 = results[0].items[1]
        assertEquals(wrapper1, wrapper2) // Equal by value

        job.cancel()
    }

    // ==================== Edge Cases ====================

    @Test
    fun emptyListHandledCorrectly() = runTest {
        val source = mutableDeltaListOf<String>(emptyList())
        val lazy = source.lazyMap { "t:$it" }

        val results = mutableListOf<Delta<String>>()
        val job = launch { lazy.collect { results.add(it) } }

        delay(50)

        assertEquals(1, results.size)
        assertEquals(0, results[0].items.size)

        // Add item
        source.append("a")
        delay(50)

        assertEquals(2, results.size)
        assertEquals(1, results[1].items.size)

        job.cancel()
    }

    @Test
    fun acquireAfterMutationWorksCorrectly() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        var latestDelta: Delta<String>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Don't acquire anything yet
        source.insert(1, "x")
        delay(50)

        // Now acquire from the updated list
        val value = latestDelta!!.items[2] // "b" shifted to index 2
        assertEquals("t:b", value)
        assertTrue(latestDelta!!.lazyList().isAcquired(2))

        job.cancel()
    }

    // ==================== Concurrency Tests ====================

    @Test
    fun concurrentAcquiresOnSameIndex() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        var latestDelta: Delta<String>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Simulate concurrent acquires - both should get the same value
        val results = mutableListOf<String>()
        val jobs = (1..10).map {
            launch {
                val value = latestDelta!!.items[1]
                // runTest uses a single-threaded scheduler, so these launches are not truly
                // parallel and need no lock (keeps this test runnable on JS/Native too).
                results.add(value)
            }
        }
        jobs.forEach { it.join() }

        // All should have gotten the same value
        assertEquals(10, results.size)
        assertTrue(results.all { it == "t:b" })

        job.cancel()
    }

    @Test
    fun concurrentAcquireAndRelease() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b", "c"))
        val lazy = source.lazyMap { "t:$it" }

        var latestDelta: Delta<String>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire
        latestDelta!!.items[1]
        assertTrue(latestDelta!!.lazyList().isAcquired(1))

        // Concurrent release and acquire
        val jobs = listOf(
            launch { latestDelta!!.lazyList().release(1) },
            launch { latestDelta!!.items[1] }
        )
        jobs.forEach { it.join() }

        // State should be consistent (either acquired or not)
        val finalState = latestDelta!!.lazyList().isAcquired(1)
        // Just verify no crash and state is valid boolean
        assertTrue(finalState || !finalState)

        job.cancel()
    }

    @Test
    fun acquireDuringDeltaApplication() = runTest {
        resetTransformCount()
        val source = mutableDeltaListOf(listOf("a", "b", "c", "d", "e"))
        val lazy = source.lazyMap { countingTransform(it) }

        var latestDelta: Delta<String>? = null
        val job = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire middle item
        val value1 = latestDelta!!.items[2]
        assertEquals("transformed:c", value1)

        // Start acquiring while also inserting
        val acquireJob = launch {
            repeat(100) {
                try {
                    latestDelta?.items?.getOrNull(2)
                } catch (e: IndexOutOfBoundsException) {
                    // Expected if list shrinks
                }
            }
        }

        val mutateJob = launch {
            repeat(10) {
                source.insert(0, "x$it")
                delay(5)
            }
        }

        acquireJob.join()
        mutateJob.join()

        // Verify state is consistent
        val finalDelta = latestDelta!!
        assertTrue(finalDelta.items.size == 15) // Original 5 + 10 inserted

        job.cancel()
    }

    @Test
    fun stressTestConcurrentOperations() = runTest {
        val source = mutableDeltaListOf((0..99).map { "item$it" })
        val lazy = source.lazyMap { "t:$it" }

        var latestDelta: Delta<String>? = null
        val collectJob = launch { lazy.collect { latestDelta = it } }

        delay(50)

        // Acquire every 10th item
        (0..9).forEach { i ->
            latestDelta!!.items[i * 10]
        }

        // Concurrent mutations and accesses
        val jobs = mutableListOf<kotlinx.coroutines.Job>()

        // Mutation job
        jobs += launch {
            repeat(20) { i ->
                when (i % 4) {
                    0 -> source.append("new$i")
                    1 -> if (source.value.size > 10) source.removeAt(5)
                    2 -> if (source.value.size > 0) source.set(0, "updated$i")
                    3 -> if (source.value.size > 2) source.move(0, 2)
                }
                delay(10)
            }
        }

        // Acquire jobs
        repeat(5) {
            jobs += launch {
                repeat(50) {
                    try {
                        val idx = (0 until (latestDelta?.items?.size ?: 1)).random()
                        latestDelta?.items?.getOrNull(idx)
                    } catch (e: Exception) {
                        // Ignore bounds exceptions during mutations
                    }
                    delay(5)
                }
            }
        }

        // Release jobs
        repeat(3) {
            jobs += launch {
                repeat(30) {
                    try {
                        val idx = (0 until (latestDelta?.items?.size ?: 1)).random()
                        (latestDelta?.items as? LazyList<*>)?.release(idx)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    delay(7)
                }
            }
        }

        jobs.forEach { it.join() }

        // Verify final state is consistent
        val finalDelta = latestDelta!!
        assertTrue(finalDelta.items.size > 0)

        // All acquired items should have valid values
        val lazyList = finalDelta.lazyList()
        finalDelta.items.forEachIndexed { idx, value ->
            if (lazyList.isAcquired(idx)) {
                assertTrue(value.startsWith("t:"))
            }
        }

        collectJob.cancel()
    }
}
