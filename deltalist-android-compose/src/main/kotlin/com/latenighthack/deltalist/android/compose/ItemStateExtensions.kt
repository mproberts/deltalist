package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.latenighthack.deltalist.LazyAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Collects a flow from an item with automatic lifecycle management.
 *
 * Flow collection starts when the composable enters composition and stops on disposal.
 * This is useful for items that expose observable state via [Flow].
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun TickingItemCard(tickingItem: TickingItem) {
 *     val tickCount = rememberItemState(
 *         item = tickingItem,
 *         key = tickingItem.id,
 *         initialValue = 0
 *     ) { it.tickCount }
 *
 *     Card {
 *         Text("Ticks: $tickCount")
 *     }
 * }
 *
 * // In LazyColumn:
 * LazyColumn {
 *     items(delta.items, key = { it.id }) { item ->
 *         TickingItemCard(item)  // Each card manages its own flow
 *     }
 * }
 * ```
 *
 * @param item The item containing the flow
 * @param key Key for recomposition identity (use item.id or similar stable identifier)
 * @param initialValue Initial state before first emission
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, updated as the flow emits
 */
@Composable
fun <T, S> rememberItemState(
    item: T,
    key: Any,
    initialValue: S,
    flowAccessor: (T) -> Flow<S>
): S {
    var state by remember(key) { mutableStateOf(initialValue) }

    LaunchedEffect(key) {
        flowAccessor(item).collectLatest { state = it }
    }

    return state
}

/**
 * Variant of [rememberItemState] that returns nullable state.
 *
 * Returns null until the first emission from the flow. Useful when there's no
 * sensible default value.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun UserProfileCard(user: User) {
 *     val profile = rememberItemStateOrNull(
 *         item = user,
 *         key = user.id
 *     ) { it.profileFlow }
 *
 *     if (profile != null) {
 *         Text("Name: ${profile.name}")
 *     } else {
 *         CircularProgressIndicator()
 *     }
 * }
 * ```
 *
 * @param item The item containing the flow
 * @param key Key for recomposition identity
 * @param flowAccessor Function to extract the [Flow] from the item
 * @return The current state value, or null before first emission
 */
@Composable
fun <T, S : Any> rememberItemStateOrNull(
    item: T,
    key: Any,
    flowAccessor: (T) -> Flow<S>
): S? = rememberItemState(item, key, null, flowAccessor)

/**
 * Combines [LazyAccess] acquisition lifecycle with flow collection.
 *
 * This helper:
 * 1. Acquires the lazy item on composition
 * 2. Collects the flow from the acquired item
 * 3. Releases the lazy item on disposal
 *
 * Use this for items wrapped in [LazyAccess] that also expose flows.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun LazyTickingCard(access: LazyAccess<TickingItem>) {
 *     val tickCount = rememberLazyItemState(
 *         access = access,
 *         key = (access as StableLazyAccess).stableId,
 *         initialValue = 0
 *     ) { it.tickCount }
 *
 *     Card {
 *         Text("Ticks: $tickCount")
 *     }
 * }
 * ```
 *
 * @param access The [LazyAccess] wrapper for the item
 * @param key Key for recomposition identity
 * @param initialValue Initial state before first emission
 * @param flowAccessor Function to extract the [Flow] from the acquired item
 * @return The current state value, updated as the flow emits
 */
@Composable
fun <T, S> rememberLazyItemState(
    access: LazyAccess<T>,
    key: Any,
    initialValue: S,
    flowAccessor: (T) -> Flow<S>
): S {
    val item = remember(key) { access.getOrAcquire() }

    DisposableEffect(key) {
        onDispose { access.release() }
    }

    return rememberItemState(item, key, initialValue, flowAccessor)
}

/**
 * Nullable variant of [rememberLazyItemState].
 *
 * Combines [LazyAccess] lifecycle management with flow collection,
 * returning null until the first flow emission.
 *
 * @param access The [LazyAccess] wrapper for the item
 * @param key Key for recomposition identity
 * @param flowAccessor Function to extract the [Flow] from the acquired item
 * @return The current state value, or null before first emission
 */
@Composable
fun <T, S : Any> rememberLazyItemStateOrNull(
    access: LazyAccess<T>,
    key: Any,
    flowAccessor: (T) -> Flow<S>
): S? = rememberLazyItemState(access, key, null, flowAccessor)
