package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.*

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.get
import com.latenighthack.deltalist.toList
import com.latenighthack.deltalist.iterator
import com.latenighthack.deltalist.isEmpty
import com.latenighthack.deltalist.isNotEmpty
import com.latenighthack.deltalist.indices
import com.latenighthack.deltalist.map
import com.latenighthack.deltalist.filter
import com.latenighthack.deltalist.forEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StableIdsTest {

    @Test
    fun `reload assigns sequential IDs`() {
        val adapter = StableIdAdapter<String>()

        val delta = Delta(listOf("A", "B", "C"), Change.Reload)
        val result = adapter.applyDelta(delta)

        assertEquals(3, result.items.size)
        assertEquals(0, result.items[0].stableId)
        assertEquals(1, result.items[1].stableId)
        assertEquals(2, result.items[2].stableId)
        assertEquals("A", result.items[0].value)
        assertEquals("B", result.items[1].value)
        assertEquals("C", result.items[2].value)
    }

    @Test
    fun `insert at end assigns new IDs`() {
        val adapter = StableIdAdapter<String>()

        // Initial load
        adapter.applyDelta(Delta(listOf("A", "B"), Change.Reload))

        // Insert at end
        val delta = Delta(
            listOf("A", "B", "C"),
            Change.Mutations(Mutation.Insert(index = 2, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A keeps ID
        assertEquals(1, result.items[1].stableId) // B keeps ID
        assertEquals(2, result.items[2].stableId) // C gets new ID
    }

    @Test
    fun `insert at beginning shifts existing IDs`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1
        adapter.applyDelta(Delta(listOf("A", "B"), Change.Reload))

        // Insert at beginning
        val delta = Delta(
            listOf("X", "A", "B"),
            Change.Mutations(Mutation.Insert(index = 0, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(2, result.items[0].stableId) // X gets new ID
        assertEquals(0, result.items[1].stableId) // A keeps original ID
        assertEquals(1, result.items[2].stableId) // B keeps original ID
    }

    @Test
    fun `insert in middle shifts IDs after insertion point`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2
        adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // Insert at index 1
        val delta = Delta(
            listOf("A", "X", "B", "C"),
            Change.Mutations(Mutation.Insert(index = 1, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A keeps ID
        assertEquals(3, result.items[1].stableId) // X gets new ID
        assertEquals(1, result.items[2].stableId) // B keeps original ID
        assertEquals(2, result.items[3].stableId) // C keeps original ID
    }

    @Test
    fun `remove at beginning shifts IDs`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2
        adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // Remove first item
        val delta = Delta(
            listOf("B", "C"),
            Change.Mutations(Mutation.Remove(index = 0, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(1, result.items[0].stableId) // B keeps original ID
        assertEquals(2, result.items[1].stableId) // C keeps original ID
    }

    @Test
    fun `remove in middle preserves surrounding IDs`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2
        adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // Remove middle item
        val delta = Delta(
            listOf("A", "C"),
            Change.Mutations(Mutation.Remove(index = 1, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A keeps ID
        assertEquals(2, result.items[1].stableId) // C keeps ID (ID 1 is gone)
    }

    @Test
    fun `update preserves IDs`() {
        val adapter = StableIdAdapter<String>()

        // Initial load
        adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // Update middle item
        val delta = Delta(
            listOf("A", "B-updated", "C"),
            Change.Mutations(Mutation.Update(index = 1, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId)
        assertEquals(1, result.items[1].stableId) // Same ID, updated value
        assertEquals(2, result.items[2].stableId)
        assertEquals("B-updated", result.items[1].value)
    }

    @Test
    fun `move forward preserves ID`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2, D=3
        adapter.applyDelta(Delta(listOf("A", "B", "C", "D"), Change.Reload))

        // Move A from index 0 to index 2 (after C)
        val delta = Delta(
            listOf("B", "C", "A", "D"),
            Change.Mutations(Mutation.Move(fromIndex = 0, toIndex = 2))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(1, result.items[0].stableId) // B
        assertEquals(2, result.items[1].stableId) // C
        assertEquals(0, result.items[2].stableId) // A keeps its ID!
        assertEquals(3, result.items[3].stableId) // D
    }

    @Test
    fun `move backward preserves ID`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2, D=3
        adapter.applyDelta(Delta(listOf("A", "B", "C", "D"), Change.Reload))

        // Move D from index 3 to index 1
        val delta = Delta(
            listOf("A", "D", "B", "C"),
            Change.Mutations(Mutation.Move(fromIndex = 3, toIndex = 1))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A
        assertEquals(3, result.items[1].stableId) // D keeps its ID!
        assertEquals(1, result.items[2].stableId) // B
        assertEquals(2, result.items[3].stableId) // C
    }

    @Test
    fun `multiple mutations in sequence`() {
        val adapter = StableIdAdapter<String>()

        // Initial load: A=0, B=1, C=2
        adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // Insert X at 0, then remove B (now at index 2)
        val delta = Delta(
            listOf("X", "A", "C"),
            Change.Mutations(
                Mutation.Insert(index = 0, count = 1),
                Mutation.Remove(index = 2, count = 1) // B was shifted to index 2
            )
        )
        val result = adapter.applyDelta(delta)

        assertEquals(3, result.items[0].stableId) // X is new
        assertEquals(0, result.items[1].stableId) // A keeps ID
        assertEquals(2, result.items[2].stableId) // C keeps ID
    }

    @Test
    fun `second reload resets IDs`() {
        val adapter = StableIdAdapter<String>()

        // First load
        val first = adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))
        assertEquals(0, first.items[0].stableId)
        assertEquals(1, first.items[1].stableId)
        assertEquals(2, first.items[2].stableId)

        // Second reload with same items
        val second = adapter.applyDelta(Delta(listOf("A", "B", "C"), Change.Reload))

        // IDs should be fresh (continuing from where we left off)
        assertEquals(3, second.items[0].stableId)
        assertEquals(4, second.items[1].stableId)
        assertEquals(5, second.items[2].stableId)
    }

    @Test
    fun `IDs are unique across entire session`() {
        val adapter = StableIdAdapter<String>()
        val allIds = mutableSetOf<Int>()

        // Load, insert, remove, reload multiple times
        var result = adapter.applyDelta(Delta(listOf("A", "B"), Change.Reload))
        allIds.addAll(result.items.map { it.stableId })

        result = adapter.applyDelta(Delta(
            listOf("A", "B", "C"),
            Change.Mutations(Mutation.Insert(2, 1))
        ))
        allIds.addAll(result.items.map { it.stableId })

        result = adapter.applyDelta(Delta(listOf("X", "Y", "Z"), Change.Reload))
        allIds.addAll(result.items.map { it.stableId })

        // All IDs should be unique (0, 1, 2, 3, 4, 5)
        assertEquals(6, allIds.size)
    }

    @Test
    fun `batch insert assigns sequential new IDs`() {
        val adapter = StableIdAdapter<String>()

        adapter.applyDelta(Delta(listOf("A"), Change.Reload))

        val delta = Delta(
            listOf("A", "B", "C", "D"),
            Change.Mutations(Mutation.Insert(index = 1, count = 3))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A
        assertEquals(1, result.items[1].stableId) // B - new
        assertEquals(2, result.items[2].stableId) // C - new
        assertEquals(3, result.items[3].stableId) // D - new
    }

    @Test
    fun `batch remove removes correct IDs`() {
        val adapter = StableIdAdapter<String>()

        adapter.applyDelta(Delta(listOf("A", "B", "C", "D", "E"), Change.Reload))

        // Remove B, C, D (indices 1, 2, 3)
        val delta = Delta(
            listOf("A", "E"),
            Change.Mutations(Mutation.Remove(index = 1, count = 3))
        )
        val result = adapter.applyDelta(delta)

        assertEquals(0, result.items[0].stableId) // A
        assertEquals(4, result.items[1].stableId) // E keeps its ID
    }

    @Test
    fun `out-of-range mutation falls back to reload instead of throwing`() {
        val adapter = StableIdAdapter<String>()
        adapter.applyDelta(Delta(listOf("A", "B"), Change.Reload))

        // A desynced mutation (removing index 5 of a 2-element mapping) must not throw;
        // it degrades to a Reload with a mapping consistent with the new items.
        val delta = Delta(
            listOf("A", "B", "C"),
            Change.Mutations(Mutation.Remove(index = 5, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertTrue(result.change is Change.Reload)
        assertEquals(3, result.items.size)
        // ids unique and consistent with the regenerated mapping
        assertEquals(3, result.items.map { it.stableId }.toSet().size)
    }

    @Test
    fun `mutation that leaves mapping size mismatched falls back to reload`() {
        val adapter = StableIdAdapter<String>()
        adapter.applyDelta(Delta(listOf("A", "B"), Change.Reload))

        // Mutations imply size 2 but the new snapshot has size 3 -> desync -> Reload.
        val delta = Delta(
            listOf("A", "B", "C"),
            Change.Mutations(Mutation.Update(index = 0, count = 1))
        )
        val result = adapter.applyDelta(delta)

        assertTrue(result.change is Change.Reload)
        assertEquals(3, result.items.size)
    }
}
