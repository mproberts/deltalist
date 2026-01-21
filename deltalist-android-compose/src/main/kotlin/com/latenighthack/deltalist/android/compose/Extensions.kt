package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList

/**
 * Collects a [DeltaList] as Compose state.
 *
 * This is the primary API for using DeltaList with Jetpack Compose. Use this extension
 * to collect the delta and then pass `delta.items` to standard Compose list APIs.
 *
 * Example with LazyColumn:
 * ```kotlin
 * @Composable
 * fun MyList(deltaList: DeltaList<StableItem<MyData>>) {
 *     val delta = deltaList.collectAsDeltaState()
 *
 *     LazyColumn {
 *         items(
 *             items = delta.items,
 *             key = { it.stableId }
 *         ) { stableItem ->
 *             MyItemCard(stableItem.value)
 *         }
 *     }
 * }
 * ```
 *
 * Example with itemsIndexed:
 * ```kotlin
 * @Composable
 * fun IndexedList(deltaList: DeltaList<Item>) {
 *     val delta = deltaList.collectAsDeltaState()
 *
 *     LazyColumn {
 *         itemsIndexed(delta.items, key = { _, item -> item.id }) { index, item ->
 *             ItemRow(index = index, item = item)
 *         }
 *     }
 * }
 * ```
 *
 * @param initial The initial list to use before the first emission. Defaults to empty.
 * @return The current [Delta] containing items and change information.
 */
@Composable
fun <T> DeltaList<T>.collectAsDeltaState(
    initial: List<T> = emptyList()
): Delta<T> {
    val delta by collectAsState(initial = Delta(initial, Change.Reload))
    return delta
}
