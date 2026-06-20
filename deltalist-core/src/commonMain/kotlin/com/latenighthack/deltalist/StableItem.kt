package com.latenighthack.deltalist

/**
 * The contract platform bindings depend on to track a list item across changes: a key that
 * is unique among the items currently in the list and that stays with an item for as long as
 * the UI should treat it as the same item (so frameworks can animate moves and recycle views).
 *
 * There are two ways to satisfy it, with different identity semantics:
 *
 * 1. **Implement it directly** on a type that already carries identity
 *    (e.g. `data class Profile(override val stableId: Int) : Stable`). The id is whatever the
 *    domain says it is — typically persistent across reloads.
 * 2. **Let [com.latenighthack.deltalist.operators.withStableIds] synthesize it**, producing a
 *    [StableItem] wrapper. Those ids are session-ephemeral: assigned from a counter as items
 *    enter the list and regenerated on every reload. Use this when the underlying data has no
 *    natural key.
 *
 * These are not interchangeable — implementing [Stable] does not reproduce `withStableIds()`'s
 * reload-regeneration or move-following; it simply exposes the identity the type already has.
 *
 * Note the id is an [Int] (the iOS binding keys `NSDiffableDataSource` on `Int32`). This fits
 * synthesized counters and types whose key is genuinely Int-sized. If your domain id is a
 * `Long`/`String`/UUID, deriving `stableId` via `hashCode()` risks collisions — prefer
 * `withStableIds()`, whose counter is collision-free.
 */
interface Stable {
    /**
     * A key, unique among the list's current items, that remains stable as the item moves.
     * Suitable for use as a key in UI frameworks (Compose's `key`, RecyclerView stable ids).
     */
    val stableId: Int
}

/**
 * Adapter that pairs an arbitrary [value] with a [stableId]. Produced by
 * [com.latenighthack.deltalist.operators.withStableIds] for data that lacks its own identity.
 * Types that already implement [Stable] do not need to be wrapped.
 */
interface StableItem<out T>: Stable {
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
