package com.latenighthack.deltalist.android.notifications

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.StableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Stable<T>(override val stableId: Int, override val value: T) : StableItem<T>

/** Identity-distinct value holder so the controller's instance-based update detection is exercised. */
private class Box(val label: String)

private class FakeSink<T> : NotificationSink<T> {
    val ops = mutableListOf<String>()
    val states = mutableMapOf<Int, Any?>()
    var postCount = 0

    override fun post(stableId: Int, value: T, state: Any?) {
        ops.add("post:$stableId")
        states[stableId] = state
        postCount++
    }

    override fun cancel(stableId: Int) {
        ops.add("cancel:$stableId")
    }

    override fun postSummary(items: List<StableItem<T>>) {
        ops.add("summary:${items.size}")
    }

    override fun cancelSummary() {
        ops.add("summaryCancel")
    }
}

private fun <T> controller(
    sink: FakeSink<T>,
    grouped: Boolean = false,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
    stateAccessor: ((T) -> Flow<Any?>)? = null,
    stateInitial: ((T) -> Any?)? = null,
    rateLimitPerSecond: Int = 8,
) = TrayController(scope, sink, grouped, stateAccessor, stateInitial, rateLimitPerSecond)

private fun <T> mutations(items: List<StableItem<T>>, vararg ops: Mutation): Delta<StableItem<T>> =
    Delta(items, Change.Mutations(ops.toList()))

private fun <T> reload(items: List<StableItem<T>>): Delta<StableItem<T>> =
    Delta(items, Change.Reload)

class TrayControllerTest {

    @Test
    fun insert_posts_new_notifications() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)

        tray.applyDelta(mutations(listOf(Stable(1, Box("a")), Stable(2, Box("b"))), Mutation.Insert(0, 2)))

        assertEquals(listOf("post:1", "post:2"), sink.ops)
    }

    @Test
    fun update_reposts_same_id_only() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)
        val a = Stable(1, Box("a"))
        tray.applyDelta(reload(listOf(a, Stable(2, Box("b")))))
        sink.ops.clear()

        // b replaced with a fresh instance at the same stable id; a is unchanged.
        tray.applyDelta(mutations(listOf(a, Stable(2, Box("b2"))), Mutation.Update(1)))

        assertEquals(listOf("post:2"), sink.ops)
    }

    @Test
    fun remove_cancels_that_id() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)
        val a = Stable(1, Box("a"))
        val c = Stable(3, Box("c"))
        tray.applyDelta(reload(listOf(a, Stable(2, Box("b")), c)))
        sink.ops.clear()

        tray.applyDelta(mutations(listOf(a, c), Mutation.Remove(1)))

        assertEquals(listOf("cancel:2"), sink.ops)
    }

    @Test
    fun move_is_a_tray_no_op() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)
        val a = Stable(1, Box("a"))
        val b = Stable(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))
        sink.ops.clear()

        // Same instances, reordered.
        tray.applyDelta(mutations(listOf(b, a), Mutation.Move(0, 1)))

        assertTrue(sink.ops.isEmpty(), "move should not touch the tray, got ${sink.ops}")
    }

    @Test
    fun reload_with_regenerated_ids_cancels_old_and_posts_new() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)
        tray.applyDelta(reload(listOf(Stable(1, Box("a")), Stable(2, Box("b")))))
        sink.ops.clear()

        tray.applyDelta(reload(listOf(Stable(3, Box("c")), Stable(4, Box("d")))))

        assertEquals(listOf("cancel:1", "cancel:2", "post:3", "post:4"), sink.ops)
    }

    @Test
    fun reload_with_same_ids_force_reposts() {
        val sink = FakeSink<Box>()
        val tray = controller(sink)
        val a = Stable(1, Box("a"))
        val b = Stable(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))
        sink.ops.clear()

        tray.applyDelta(reload(listOf(a, b)))

        assertEquals(listOf("post:1", "post:2"), sink.ops)
    }

    @Test
    fun grouping_posts_and_clears_summary() {
        val sink = FakeSink<Box>()
        val tray = controller(sink, grouped = true)

        tray.applyDelta(mutations(listOf(Stable(1, Box("a"))), Mutation.Insert(0, 1)))
        assertEquals(listOf("post:1", "summary:1"), sink.ops)
        sink.ops.clear()

        tray.applyDelta(mutations(emptyList(), Mutation.Remove(0)))
        assertEquals(listOf("cancel:1", "summaryCancel"), sink.ops)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun item_state_emissions_repost_and_coalesce_under_rate_limit() = runTest {
        val sink = FakeSink<Box>()
        val state = MutableStateFlow<Any?>(0)
        val tray = controller(
            sink,
            scope = backgroundScope,
            stateAccessor = { state },
            stateInitial = { 0 },
            rateLimitPerSecond = 8, // 125ms window
        )

        tray.applyDelta(mutations(listOf(Stable(1, Box("a"))), Mutation.Insert(0, 1)))
        assertEquals(1, sink.postCount)
        assertEquals(0, sink.states[1])

        // Three rapid updates inside one sampling window collapse to a single re-post of the latest.
        state.value = 1
        state.value = 2
        state.value = 3
        advanceTimeBy(130)
        runCurrent()

        assertEquals(2, sink.postCount)
        assertEquals(3, sink.states[1])
    }
}
