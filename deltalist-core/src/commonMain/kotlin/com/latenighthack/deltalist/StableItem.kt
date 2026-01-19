package com.latenighthack.deltalist

/**
 * Wraps an item with a session-stable integer identifier.
 *
 * The stableId is unique within the current binding session and follows the item
 * as it moves through the list due to insertions, deletions, and moves.
 * On reload, all IDs are regenerated.
 *
 * Platform bindings should use [stableId] as the key for UI framework item tracking
 * (e.g., Compose's key parameter, RecyclerView's stable IDs).
 */
interface StableItem<out T> {
    /**
     * A session-unique integer identifier that remains stable as the item moves.
     * This ID is suitable for use as a key in UI frameworks.
     */
    val stableId: Int

    /**
     * The underlying value.
     */
    val value: T
}

/**
 * Combines [LazyAccess] with a stable identifier.
 *
 * Use this when you need both lazy acquisition semantics and stable IDs for UI binding.
 */
interface StableLazyAccess<out T> : LazyAccess<T> {
    /**
     * A session-unique integer identifier that remains stable as the item moves.
     */
    val stableId: Int
}

/**
 * Simple implementation of StableItem.
 */
internal data class StableItemImpl<T>(
    override val stableId: Int,
    override val value: T
) : StableItem<T>

/**
 * Implementation of StableLazyAccess that delegates to an underlying LazyAccess.
 */
internal class StableLazyAccessImpl<T>(
    override val stableId: Int,
    private val delegate: LazyAccess<T>
) : StableLazyAccess<T> {
    override fun getOrAcquire(): T = delegate.getOrAcquire()
    override fun release() = delegate.release()
    override val isAcquired: Boolean get() = delegate.isAcquired
}
