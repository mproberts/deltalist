package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.LazyList

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

/**
 * Accesses an item from a list with automatic lazy lifecycle management.
 *
 * When the list is a [LazyList] (e.g., from [lazyMap().withStableIds()]):
 * - The item is acquired on first access
 * - The item is released when the composable leaves composition
 *
 * For regular lists, this simply returns the item at the given index.
 *
 * Example:
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
 *             // Automatic lifecycle management for lazy items
 *             val item = delta.items.rememberItem(
 *                 index = delta.items.indexOf(stableItem),
 *                 key = stableItem.stableId
 *             )
 *             MyItemCard(item.value)
 *         }
 *     }
 * }
 * ```
 *
 * Note: For most use cases, you can access items directly and rely on composition
 * lifecycle. This function is useful when you need explicit control over release timing.
 *
 * @param index The index of the item to access
 * @param key A stable key for the item (used for DisposableEffect identity)
 * @return The item at the given index
 */
@Composable
fun <T> List<T>.rememberItem(index: Int, key: Any): T {
    val item = remember(key) { this[index] }

    if (this is LazyList<T>) {
        DisposableEffect(key) {
            onDispose { release(index) }
        }
    }

    return item
}

/**
 * Accesses an item from a list by key with automatic lazy lifecycle management.
 *
 * This is a convenience function for accessing items when you have the item
 * but need lifecycle management. Useful in LazyColumn/LazyRow item lambdas.
 *
 * When the list is a [LazyList]:
 * - The item is released when the composable leaves composition
 *
 * Example:
 * ```kotlin
 * LazyColumn {
 *     items(
 *         items = delta.items,
 *         key = { it.stableId }
 *     ) { stableItem ->
 *         // Use rememberLazyItem to ensure proper release
 *         delta.items.rememberLazyItem(stableItem.stableId)
 *         MyItemCard(stableItem.value)
 *     }
 * }
 * ```
 *
 * @param key The stable key for the item
 * @param index The index of the item (optional, for release - uses key for identity)
 */
@Composable
fun <T> List<T>.rememberLazyItem(key: Any, index: Int) {
    if (this is LazyList<T>) {
        DisposableEffect(key) {
            onDispose { release(index) }
        }
    }
}
