package com.latenighthack.deltalist

/**
 * A list that supports lazy acquisition and release of items.
 *
 * Platform adapters (RecyclerView, Compose) detect this interface and automatically
 * manage item lifecycle - acquiring items as they enter the viewport and releasing
 * them when they leave.
 *
 * Accessing items via [get] automatically acquires them. Use [release] to free
 * cached values when items leave the viewport, and [releaseAll] during cleanup.
 *
 * Example usage in a Compose adapter:
 * ```kotlin
 * @Composable
 * fun <T> List<T>.rememberItem(index: Int, key: Any): T {
 *     if (this is LazyList<T>) {
 *         DisposableEffect(key) { onDispose { release(index) } }
 *     }
 *     return this[index]
 * }
 * ```
 *
 * Example usage in a RecyclerView adapter:
 * ```kotlin
 * override fun onViewDetachedFromWindow(holder: VH) {
 *     super.onViewDetachedFromWindow(holder)
 *     items.releaseIfLazy(holder.bindingAdapterPosition)
 * }
 * ```
 */
interface LazyList<out T> : List<T> {
    /**
     * Releases the cached value at the given index, allowing it to be garbage collected.
     *
     * After release, the next access to this index will recompute the value.
     * Platform bindings should call this when items scroll out of view.
     *
     * @param index The index of the item to release
     */
    fun release(index: Int)

    /**
     * Releases all cached values in the list.
     *
     * Call this during cleanup (e.g., when unbinding from lifecycle) to free all resources.
     */
    fun releaseAll()

    /**
     * Whether the item at the given index currently has an acquired (cached) value.
     *
     * @param index The index to check
     * @return true if the item is currently acquired, false otherwise
     */
    fun isAcquired(index: Int): Boolean
}

/**
 * Releases the item at the given index if this list is a [LazyList].
 *
 * This is a convenience extension for platform adapters that need to conditionally
 * release items without knowing whether the underlying list supports lazy access.
 *
 * @param index The index of the item to release
 */
fun <T> List<T>.releaseIfLazy(index: Int) {
    (this as? LazyList<*>)?.release(index)
}

/**
 * Releases all items if this list is a [LazyList].
 *
 * This is a convenience extension for platform adapters that need to conditionally
 * release all items without knowing whether the underlying list supports lazy access.
 */
fun <T> List<T>.releaseAllIfLazy() {
    (this as? LazyList<*>)?.releaseAll()
}
