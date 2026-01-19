package com.latenighthack.deltalist

/**
 * Provides lazy access to a transformed item in a lazy-mapped delta list.
 *
 * This interface allows platform bindings (RecyclerView, Compose, SwiftUI, etc.)
 * to acquire and release items as they enter and leave the viewport, enabling
 * efficient memory usage for large lists with expensive transformations.
 *
 * The lazy access tracks position changes automatically - if an item moves due to
 * insertions/deletions elsewhere in the list, the cached value moves with it.
 */
interface LazyAccess<out T> {
    /**
     * Gets the transformed value, computing and caching it if not already acquired.
     *
     * If this item is already acquired, returns the same cached instance.
     * If not, computes the transformation and caches it.
     *
     * @return The transformed value
     */
    fun getOrAcquire(): T

    /**
     * Releases the cached value, allowing it to be garbage collected.
     *
     * After release, the next call to [getOrAcquire] will recompute the value.
     * Platform bindings should call this when items scroll out of view.
     */
    fun release()

    /**
     * Whether this item currently has an acquired (cached) value.
     */
    val isAcquired: Boolean
}
