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
 * Simple implementation of StableItem.
 */
internal data class StableItemImpl<T>(
    override val stableId: Int,
    override val value: T
) : StableItem<T>
