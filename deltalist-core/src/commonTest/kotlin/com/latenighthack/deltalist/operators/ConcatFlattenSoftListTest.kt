package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
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
 * Tests for Concat and Flatten operators' SoftList behavior.
 *
 * Concat:
 * - Currently does NOT implement SoftList (documented limitation)
 * - Should work correctly with regular lists
 *
 * Flatten:
 * - FlattenedSectionList implements SoftList
 * - FlattenedItemsList implements SoftList
 * - Should propagate soft access from section items
 */
class ConcatFlattenSoftListTest {

    /**
     * Test helper: A SoftList that simulates pagination.
     */
    private class MockSoftList<T>(
        private val loadedItems: List<T>,
        private val estimatedTotal: Int
    ) : AbstractList<T>(), SoftList<T> {

        override val size: Int = estimatedTotal

        override fun get(index: Int): T {
            if (index < 0) throw IndexOutOfBoundsException("Index $index is negative")
            if (index >= loadedItems.size) {
                throw IndexOutOfBoundsException("Index $index beyond loaded (${loadedItems.size})")
            }
            return loadedItems[index]
        }

        override fun softGet(index: Int): SoftValue<T>? {
            if (index < 0 || index >= size) return null
            return if (index < loadedItems.size) {
                SoftValue.Present(loadedItems[index])
            } else {
                SoftValue.NotLoaded()
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

    // ==================== Concat Tests ====================

    @Test
    fun concat_basicConcatenation() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(6, results.last().items.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), results.last().items.toList())
    }

    @Test
    fun concat_mutationsInFirstList() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)

        // Insert in first list
        first.value = Delta(listOf(1, 2, 10, 3), Change.Mutations(listOf(Mutation.Insert(2, 1))))

        delay(50)
        job.cancel()

        val lastDelta = results.last()
        assertEquals(7, lastDelta.items.size)
        assertEquals(listOf(1, 2, 10, 3, 4, 5, 6), lastDelta.items.toList())
    }

    @Test
    fun concat_mutationsInBothListsOffsetCorrectly() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)

        // Emit mutations from both flows - concat only produces Mutations if BOTH have Mutations
        first.value = Delta(listOf(1, 2, 10, 3), Change.Mutations(listOf(Mutation.Insert(2, 1))))
        second.value = Delta(listOf(4, 20, 5, 6), Change.Mutations(listOf(Mutation.Insert(1, 1))))

        delay(50)
        job.cancel()

        val lastDelta = results.last()
        assertEquals(8, lastDelta.items.size)
        assertEquals(listOf(1, 2, 10, 3, 4, 20, 5, 6), lastDelta.items.toList())

        // Mutations should be present from both lists
        val change = lastDelta.change as Change.Mutations
        assertEquals(2, change.operations.size)

        // First mutation at original index
        val insert1 = change.operations[0] as Mutation.Insert
        assertEquals(2, insert1.index)

        // Second mutation offset by first list size (4)
        val insert2 = change.operations[1] as Mutation.Insert
        assertEquals(5, insert2.index) // 1 + 4 (first list size after insert)
    }

    @Test
    fun concat_headerAddsItemAtStart() = runTest {
        val source = MutableStateFlow(Delta(listOf(2, 3, 4), Change.Reload))
        val withHeader = source.header(1)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { withHeader.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(listOf(1, 2, 3, 4), results.last().items.toList())
    }

    @Test
    fun concat_footerAddsItemAtEnd() = runTest {
        val source = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val withFooter = source.footer(4)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { withFooter.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(listOf(1, 2, 3, 4), results.last().items.toList())
    }

    // ==================== Concat SoftList Tests ====================

    @Test
    fun concat_propagatesSoftListFromFirstList() = runTest {
        val softFirst = MockSoftList(
            loadedItems = listOf(1, 2, 3),
            estimatedTotal = 10
        )
        val regularSecond = listOf(100, 200, 300)

        val first = MutableStateFlow(Delta(softFirst, Change.Reload))
        val second = MutableStateFlow(Delta(regularSecond, Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>, "Concatenated list should be SoftList")

        // Size: 10 (first estimated) + 3 (second) = 13
        assertEquals(13, items.size)

        // First list loaded items - Present
        val soft0 = (items as SoftList<Int>).softGet(0)
        assertIs<SoftValue.Present<Int>>(soft0)
        assertEquals(1, soft0.value)

        // First list unloaded items - NotLoaded
        val soft5 = items.softGet(5)
        assertTrue((soft5 is SoftValue.NotLoaded))

        // Second list items - Present (regular list)
        val soft10 = items.softGet(10)
        assertIs<SoftValue.Present<Int>>(soft10)
        assertEquals(100, soft10.value)
    }

    @Test
    fun concat_propagatesSoftListFromSecondList() = runTest {
        val regularFirst = listOf(1, 2, 3)
        val softSecond = MockSoftList(
            loadedItems = listOf(100, 200),
            estimatedTotal = 10
        )

        val first = MutableStateFlow(Delta(regularFirst, Change.Reload))
        val second = MutableStateFlow(Delta(softSecond, Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>, "Concatenated list should be SoftList")

        // Size: 3 (first) + 10 (second estimated) = 13
        assertEquals(13, items.size)

        // First list items - Present
        val soft0 = (items as SoftList<Int>).softGet(0)
        assertIs<SoftValue.Present<Int>>(soft0)
        assertEquals(1, soft0.value)

        // Second list loaded items - Present
        val soft3 = items.softGet(3)
        assertIs<SoftValue.Present<Int>>(soft3)
        assertEquals(100, soft3.value)

        // Second list unloaded items - NotLoaded
        val soft8 = items.softGet(8)
        assertTrue((soft8 is SoftValue.NotLoaded))
    }

    @Test
    fun concat_bothSoftLists() = runTest {
        val softFirst = MockSoftList(
            loadedItems = listOf(1, 2),
            estimatedTotal = 5
        )
        val softSecond = MockSoftList(
            loadedItems = listOf(10, 20),
            estimatedTotal = 5
        )

        val first = MutableStateFlow(Delta(softFirst, Change.Reload))
        val second = MutableStateFlow(Delta(softSecond, Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>, "Concatenated list should be SoftList")

        // Size: 5 + 5 = 10
        assertEquals(10, items.size)

        // First list loaded - Present
        assertIs<SoftValue.Present<Int>>((items as SoftList<Int>).softGet(0))
        assertIs<SoftValue.Present<Int>>(items.softGet(1))

        // First list unloaded - NotLoaded
        assertTrue(items.softGet(3) is SoftValue.NotLoaded)

        // Second list loaded - Present
        val soft5 = items.softGet(5)
        assertIs<SoftValue.Present<Int>>(soft5)
        assertEquals(10, soft5.value)

        // Second list unloaded - NotLoaded
        assertTrue(items.softGet(8) is SoftValue.NotLoaded)

        // Out of bounds - null
        assertNull(items.softGet(10))
        assertNull(items.softGet(-1))
    }

    @Test
    fun concat_regularListsReturnsAllPresent() = runTest {
        val first = MutableStateFlow(Delta(listOf(1, 2, 3), Change.Reload))
        val second = MutableStateFlow(Delta(listOf(4, 5, 6), Change.Reload))

        val concatenated = first.concat(second)

        val results = mutableListOf<Delta<Int>>()
        val job = launch { concatenated.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>, "Concatenated list should be SoftList")

        // All items should be Present for regular lists
        for (i in 0 until items.size) {
            val soft = (items as SoftList<Int>).softGet(i)
            assertIs<SoftValue.Present<Int>>(soft)
        }
    }

    // ==================== Flatten SoftList Tests ====================

    @Test
    fun flattenItems_propagatesSoftListFromSections() = runTest {
        val sectionItems = MockSoftList(
            loadedItems = listOf("a", "b", "c"),
            estimatedTotal = 10
        )

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Header1", sectionItems)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flattenItems()

        val results = mutableListOf<Delta<String>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<String>, "Flattened list should be SoftList")

        // Check loaded items return Present
        val soft0 = (items as SoftList<String>).softGet(0)
        assertIs<SoftValue.Present<String>>(soft0)
        assertEquals("a", soft0.value)

        // Check unloaded items return NotLoaded
        val soft5 = items.softGet(5)
        assertTrue((soft5 is SoftValue.NotLoaded))

        // Check out of bounds returns null
        assertNull(items.softGet(15))
    }

    @Test
    fun flatten_withHeaderAndItems_propagatesSoftList() = runTest {
        val sectionItems = MockSoftList(
            loadedItems = listOf(1, 2, 3),
            estimatedTotal = 10
        )

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Section A", sectionItems)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flatten(
            header = { header -> "Header: $header" },
            item = { item -> "Item: $item" }
        )

        val results = mutableListOf<Delta<String>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<String>, "Flattened list should be SoftList")

        // Index 0 is header - always Present
        val soft0 = (items as SoftList<String>).softGet(0)
        assertIs<SoftValue.Present<String>>(soft0)
        assertEquals("Header: Section A", soft0.value)

        // Index 1, 2, 3 are loaded items - Present
        val soft1 = items.softGet(1)
        assertIs<SoftValue.Present<String>>(soft1)
        assertEquals("Item: 1", soft1.value)

        // Index 4+ are unloaded items - NotLoaded
        val soft4 = items.softGet(4)
        assertTrue((soft4 is SoftValue.NotLoaded))
    }

    @Test
    fun flatten_multipleSections_propagatesSoftList() = runTest {
        val section1Items = MockSoftList(
            loadedItems = listOf(1, 2),
            estimatedTotal = 5
        )
        val section2Items = MockSoftList(
            loadedItems = listOf(10, 20),
            estimatedTotal = 5
        )

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Section A", section1Items),
                    Section("Section B", section2Items)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flattenItems()

        val results = mutableListOf<Delta<Int>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>, "Flattened list should be SoftList")

        // Total size: 5 + 5 = 10
        assertEquals(10, items.size)

        // First section loaded items
        val soft0 = (items as SoftList<Int>).softGet(0)
        assertIs<SoftValue.Present<Int>>(soft0)
        assertEquals(1, soft0.value)

        val soft1 = items.softGet(1)
        assertIs<SoftValue.Present<Int>>(soft1)
        assertEquals(2, soft1.value)

        // First section unloaded items (indices 2-4)
        val soft3 = items.softGet(3)
        assertTrue((soft3 is SoftValue.NotLoaded))

        // Second section loaded items (indices 5-6)
        val soft5 = items.softGet(5)
        assertIs<SoftValue.Present<Int>>(soft5)
        assertEquals(10, soft5.value)

        // Second section unloaded items (indices 7-9)
        val soft8 = items.softGet(8)
        assertTrue((soft8 is SoftValue.NotLoaded))
    }

    @Test
    fun flatten_withFooter_propagatesSoftList() = runTest {
        val sectionItems = MockSoftList(
            loadedItems = listOf(1, 2, 3),
            estimatedTotal = 5
        )

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Section", sectionItems)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flatten(
            header = { "Header" },
            item = { "Item $it" },
            footer = { header, items -> "Footer ($header)" }
        )

        val results = mutableListOf<Delta<String>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<String>, "Flattened list should be SoftList")

        // Size: 1 header + 5 items + 1 footer = 7
        assertEquals(7, items.size)

        // Header at index 0 - Present
        val soft0 = (items as SoftList<String>).softGet(0)
        assertIs<SoftValue.Present<String>>(soft0)
        assertEquals("Header", soft0.value)

        // Loaded items at indices 1-3 - Present
        val soft1 = items.softGet(1)
        assertIs<SoftValue.Present<String>>(soft1)
        assertEquals("Item 1", soft1.value)

        // Unloaded items at indices 4-5 - NotLoaded
        val soft5 = items.softGet(5)
        assertTrue((soft5 is SoftValue.NotLoaded))

        // Footer at index 6 - Present
        val soft6 = items.softGet(6)
        assertIs<SoftValue.Present<String>>(soft6)
        assertEquals("Footer (Section)", soft6.value)
    }

    @Test
    fun flattenItems_regularListReturnsAllPresent() = runTest {
        val regularList = listOf("a", "b", "c")

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Header", regularList)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flattenItems()

        val results = mutableListOf<Delta<String>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<String>)

        // All items should be Present for regular list
        for (i in 0 until items.size) {
            assertIs<SoftValue.Present<String>>((items as SoftList<String>).softGet(i))
        }
    }

    @Test
    fun flattenItems_emptySection() = runTest {
        val emptyList = MockSoftList<Int>(emptyList(), 0)

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Empty", emptyList)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flattenItems()

        val results = mutableListOf<Delta<Int>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        assertEquals(0, results.last().items.size)
    }

    @Test
    fun flattenItems_mixedRegularAndSoftLists() = runTest {
        val regularSection = listOf(1, 2, 3)
        val softSection = MockSoftList(
            loadedItems = listOf(10, 20),
            estimatedTotal = 5
        )

        val sectionedFlow = MutableStateFlow(
            SectionedDelta(
                sections = listOf(
                    Section("Regular", regularSection),
                    Section("Soft", softSection)
                ),
                change = SectionedChange.Reload
            )
        )

        val flattened = sectionedFlow.flattenItems()

        val results = mutableListOf<Delta<Int>>()
        val job = launch { flattened.collect { results.add(it) } }

        delay(50)
        job.cancel()

        val items = results.last().items
        assertTrue(items is SoftList<Int>)

        // Size: 3 (regular) + 5 (soft) = 8
        assertEquals(8, items.size)

        // Regular section items (0-2) - Present
        assertIs<SoftValue.Present<Int>>((items as SoftList<Int>).softGet(0))
        assertIs<SoftValue.Present<Int>>(items.softGet(1))
        assertIs<SoftValue.Present<Int>>(items.softGet(2))

        // Soft section loaded items (3-4) - Present
        val soft3 = items.softGet(3)
        assertIs<SoftValue.Present<Int>>(soft3)
        assertEquals(10, soft3.value)

        // Soft section unloaded items (5-7) - NotLoaded
        assertTrue(items.softGet(6) is SoftValue.NotLoaded)
    }
}
