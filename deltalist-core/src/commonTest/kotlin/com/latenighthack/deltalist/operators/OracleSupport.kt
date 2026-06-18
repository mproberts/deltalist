@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedDeltaList
import com.latenighthack.deltalist.SectionMutation
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.applyChange
import com.latenighthack.deltalist.softLoadedItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared oracle/fuzz harness for delta operators.
 *
 * The single most valuable invariant a diffing list can have: applying each emitted [Change]
 * to the previously-reconstructed list must reproduce the snapshot the delta carries. This file
 * centralizes that check (reusing the production [applyChange] for flat lists, plus a test-only
 * applier for the sectioned subsystem) and the deterministic drivers needed to exercise
 * `combine`-based operators without [MutableStateFlow] conflation hiding intermediate emissions.
 */

// ============================================================================
// Flat oracle
// ============================================================================

/**
 * Asserts the flat delta sequence is self-consistent: the first delta is a [Change.Reload] and
 * folding every [Change] via [applyChange] onto a running list reproduces each delta's loaded
 * snapshot exactly.
 */
fun <T> List<Delta<T>>.assertFlatOracle() {
    assertTrue(isNotEmpty(), "expected at least one delta")
    assertTrue(this[0].change is Change.Reload, "first delta must be Reload, was ${this[0].change}")

    var running = emptyList<T>()
    for ((i, delta) in withIndex()) {
        val snapshot = delta.items.softLoadedItems()
        running = try {
            applyChange(running, delta.change, snapshot)
        } catch (t: Throwable) {
            throw AssertionError("delta #$i ${delta.change} threw applying onto $running: ${t.message}")
        }
        assertEquals(
            snapshot, running,
            "delta #$i ${delta.change} did not reconstruct the carried snapshot"
        )
    }
}

// ============================================================================
// Soft oracle (placeholder-aware: validates NotLoaded slots too)
// ============================================================================

private fun <T> SoftList<T>.toSoftValues(): List<SoftValue<T>> =
    (0 until size).map { softGet(it) ?: error("softGet($it) returned null within size=$size") }

private fun <T> softValuesEqual(a: List<SoftValue<T>>, b: List<SoftValue<T>>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        val same = when {
            x is SoftValue.Present && y is SoftValue.Present -> x.value == y.value
            x is SoftValue.NotLoaded && y is SoftValue.NotLoaded -> true
            else -> false
        }
        if (!same) return false
    }
    return true
}

private fun <T> applySoft(
    running: List<SoftValue<T>>,
    change: Change,
    newSnapshot: SoftList<T>
): List<SoftValue<T>> = when (change) {
    is Change.Reload -> newSnapshot.toSoftValues()
    is Change.Mutations -> {
        val working = running.toMutableList()
        for (op in change.operations) when (op) {
            is Mutation.Insert -> for (i in 0 until op.count) {
                working.add(op.index + i, newSnapshot.softGet(op.index + i)!!)
            }
            is Mutation.Remove -> repeat(op.count) { working.removeAt(op.index) }
            is Mutation.Update -> for (i in 0 until op.count) {
                working[op.index + i] = newSnapshot.softGet(op.index + i)!!
            }
            is Mutation.Move -> repeat(op.count) { k ->
                val v = working.removeAt(op.fromIndex + k)
                working.add(op.toIndex + k, v)
            }
        }
        working
    }
}

/**
 * Placeholder-aware oracle for soft/paginated sources: folds each [Change] over a running list of
 * [SoftValue]s (sourcing inserted/updated slots from the new snapshot — which may themselves be
 * [SoftValue.NotLoaded]) and asserts it reproduces the carried snapshot position-for-position,
 * including load state. The soundest check for filter/pagination where the flat oracle can't model
 * unloaded tails.
 */
fun <T> List<Delta<T>>.assertSoftOracle() {
    assertTrue(isNotEmpty(), "expected at least one delta")
    assertTrue(this[0].change is Change.Reload, "first delta must be Reload, was ${this[0].change}")

    var running = emptyList<SoftValue<T>>()
    for ((i, delta) in withIndex()) {
        val snapshot = delta.items.toSoftValues()
        running = try {
            applySoft(running, delta.change, delta.items)
        } catch (t: Throwable) {
            throw AssertionError("soft delta #$i ${delta.change} threw: ${t.message}")
        }
        assertTrue(
            softValuesEqual(snapshot, running),
            "soft delta #$i ${delta.change} did not reconstruct the carried snapshot (size ${snapshot.size} vs ${running.size})"
        )
    }
}

// ============================================================================
// Sectioned oracle (test-only applier mirroring Apply.kt semantics)
// ============================================================================

/**
 * Reference applier for [SectionedChange], the sectioned analogue of [applyChange]. Section-level
 * mutations run sequentially in running coordinates; inserted/updated sections are sourced from
 * [new]. [SectionedChange.Items] delegates the per-item fold to [applyChange].
 */
fun <S, T> applySectioned(
    old: List<Section<S, T>>,
    change: SectionedChange,
    new: List<Section<S, T>>
): List<Section<S, T>> = when (change) {
    is SectionedChange.Reload -> new
    is SectionedChange.Sections -> {
        val working = old.toMutableList()
        for (op in change.mutations) {
            when (op) {
                is SectionMutation.Insert -> for (i in 0 until op.count) {
                    working.add(op.index + i, new[op.index + i])
                }
                is SectionMutation.Remove -> repeat(op.count) { working.removeAt(op.index) }
                is SectionMutation.Update -> working[op.index] = new[op.index]
                is SectionMutation.Move -> {
                    val s = working.removeAt(op.fromIndex)
                    working.add(op.toIndex, s)
                }
            }
        }
        working
    }
    is SectionedChange.Items -> {
        val working = old.toMutableList()
        val target = working[change.section]
        val newItems = new[change.section].items.softLoadedItems()
        val rebuilt = applyChange(target.items.softLoadedItems(), Change.Mutations(change.mutations), newItems)
        working[change.section] = Section(target.header, rebuilt)
        working
    }
}

private fun <S, T> sectionsEqual(a: List<Section<S, T>>, b: List<Section<S, T>>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i].header != b[i].header) return false
        if (a[i].items.softLoadedItems() != b[i].items.softLoadedItems()) return false
    }
    return true
}

/**
 * Asserts the sectioned delta sequence is self-consistent under [applySectioned], comparing
 * structurally on headers + loaded items (ignoring SoftList identity).
 */
fun <S, T> List<SectionedDelta<S, T>>.assertSectionedOracle() {
    assertTrue(isNotEmpty(), "expected at least one sectioned delta")
    assertTrue(this[0].change is SectionedChange.Reload, "first delta must be Reload, was ${this[0].change}")

    var running = emptyList<Section<S, T>>()
    for ((i, delta) in withIndex()) {
        running = try {
            applySectioned(running, delta.change, delta.sections)
        } catch (t: Throwable) {
            throw AssertionError("sectioned delta #$i ${delta.change} threw: ${t.message}")
        }
        assertTrue(
            sectionsEqual(delta.sections, running),
            "sectioned delta #$i ${delta.change} did not reconstruct the carried sections"
        )
    }
}

// ============================================================================
// Deterministic drivers
// ============================================================================

/**
 * Collects [flow] in the background while [drive] mutates upstream [MutableStateFlow] sources,
 * calling [advanceUntilIdle] between steps so each emission is processed (no conflation). Returns
 * every emission in order.
 */
fun <T> TestScope.collectDriven(
    flow: Flow<T>,
    drive: TestScope.() -> Unit
): List<T> {
    val out = mutableListOf<T>()
    val job = launch { flow.collect { out.add(it) } }
    advanceUntilIdle()
    drive()
    advanceUntilIdle()
    job.cancel()
    return out
}

/** Sets a source's value and immediately drains the scheduler so the emission is fully processed. */
fun <T> TestScope.step(source: MutableStateFlow<T>, value: T) {
    source.value = value
    advanceUntilIdle()
}

// ============================================================================
// Fuzz support
// ============================================================================

/**
 * Produces a random single-op mutation valid for [list], returning the resulting list and the
 * [Mutation] that describes the transition (exactly as a mutable delta source would emit it).
 */
fun randomFlatStep(
    list: List<Int>,
    rng: Random,
    nextValue: () -> Int,
    allowMove: Boolean = true
): Pair<List<Int>, Mutation> {
    val working = list.toMutableList()
    val choices = buildList {
        add("insert")
        if (working.isNotEmpty()) {
            add("remove"); add("update")
            if (allowMove && working.size >= 2) add("move")
        }
    }
    return when (choices[rng.nextInt(choices.size)]) {
        "insert" -> {
            val i = rng.nextInt(0, working.size + 1)
            working.add(i, nextValue())
            working.toList() to Mutation.Insert(i, 1)
        }
        "remove" -> {
            val i = rng.nextInt(working.size)
            working.removeAt(i)
            working.toList() to Mutation.Remove(i, 1)
        }
        "update" -> {
            val i = rng.nextInt(working.size)
            working[i] = nextValue()
            working.toList() to Mutation.Update(i, 1)
        }
        else -> {
            val from = rng.nextInt(working.size)
            val to = rng.nextInt(working.size)
            val item = working.removeAt(from)
            working.add(to, item)
            working.toList() to Mutation.Move(from, to, 1)
        }
    }
}
