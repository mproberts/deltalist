package com.latenighthack.deltalist.android.recyclerview

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base adapter for RecyclerView that automatically handles DeltaList updates.
 *
 * Extend this class and implement [onCreateViewHolder] and [onBindViewHolder].
 * Delta mutations are automatically applied as efficient RecyclerView notifications.
 *
 * ## Stable IDs
 * When the item type is [StableItem] or [StableLazyAccess], stable IDs are automatically
 * enabled and [getItemId] returns the stable ID. Otherwise, override [getItemId] manually
 * and call [setHasStableIds(true)] if you need stable IDs.
 *
 * ## View Types
 * Override [getItemViewType] to support multiple view types. Access items via the
 * protected [items] property or [getItem] method.
 *
 * Example:
 * ```kotlin
 * class MyAdapter(deltaList: DeltaList<StableItem<MyData>>) :
 *     DeltaAdapter<StableItem<MyData>, MyViewHolder>(deltaList) {
 *
 *     override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder { ... }
 *
 *     override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
 *         val item = getItem(position)
 *         holder.bind(item.value)
 *     }
 * }
 * ```
 */
abstract class DeltaAdapter<T, VH : RecyclerView.ViewHolder>(
    private val deltaList: DeltaList<T>
) : RecyclerView.Adapter<VH>() {

    /**
     * The current list of items. Updated automatically when deltas are received.
     */
    protected var items: List<T> = emptyList()
        private set

    private var job: Job? = null
    private var stableIdMode: StableIdMode = StableIdMode.Unknown

    private enum class StableIdMode {
        Unknown,
        StableItem,
        StableLazyAccess,
        None
    }

    /**
     * Starts collecting deltas from the DeltaList and applying them to this adapter.
     * Call this in your Activity/Fragment's onCreate or when attaching the adapter.
     *
     * Subclasses can override this to capture the lifecycle owner or perform setup
     * before delta collection starts. Always call super.bind(owner).
     */
    open fun bind(owner: LifecycleOwner) {
        job?.cancel()
        job = owner.lifecycleScope.launch {
            deltaList.collect { delta -> applyDelta(delta) }
        }
    }

    /**
     * Stops collecting deltas. Call this when the adapter is no longer needed.
     */
    fun unbind() {
        job?.cancel()
        job = null
    }

    private fun applyDelta(delta: Delta<T>) {
        items = delta.items

        // Auto-detect stable ID mode on first non-empty delta
        if (stableIdMode == StableIdMode.Unknown && items.isNotEmpty()) {
            stableIdMode = when (items.first()) {
                is StableItem<*> -> StableIdMode.StableItem
                is StableLazyAccess<*> -> StableIdMode.StableLazyAccess
                else -> StableIdMode.None
            }
            if (stableIdMode != StableIdMode.None) {
                setHasStableIds(true)
            }
        }

        when (val change = delta.change) {
            is Change.Reload -> notifyDataSetChanged()
            is Change.Mutations -> {
                change.operations.forEach { mutation ->
                    when (mutation) {
                        is Mutation.Insert -> notifyItemRangeInserted(mutation.index, mutation.count)
                        is Mutation.Remove -> notifyItemRangeRemoved(mutation.index, mutation.count)
                        is Mutation.Update -> notifyItemRangeChanged(mutation.index, mutation.count)
                        is Mutation.Move -> {
                            repeat(mutation.count) { i ->
                                notifyItemMoved(mutation.fromIndex + i, mutation.toIndex + i)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Returns the item at the given position.
     * This is useful for subclasses in [onBindViewHolder] and for external code
     * that needs to access items (e.g., ItemTouchHelper callbacks).
     *
     * Note: For paginated lists, this may trigger a fetch for unloaded items.
     * Use [softGetItem] if you need to check loading state without side effects.
     */
    fun getItem(position: Int): T = items[position]

    /**
     * Returns the item at the given position as a [SoftValue] without triggering side effects.
     *
     * This is useful for:
     * - Determining view types in [getItemViewType] for paginated lists
     * - Checking if an item is loaded before accessing it
     * - Avoiding accidental fetch triggers during layout passes
     *
     * For regular (non-paginated) lists, this always returns [SoftValue.Present].
     * For paginated lists backed by [SoftList], this may return [SoftValue.NotLoaded].
     *
     * @return [SoftValue.Present] if loaded, [SoftValue.NotLoaded] if not loaded,
     *         or null if the index is out of bounds.
     */
    fun softGetItem(position: Int): SoftValue<T>? = items.softGetOrNull(position)

    /**
     * Returns the stable ID for the item at the given position.
     *
     * When items are [StableItem] or [StableLazyAccess], this automatically returns
     * their stable ID. Otherwise, returns [RecyclerView.NO_ID] by default.
     * Override this method if you need custom stable ID logic.
     */
    override fun getItemId(position: Int): Long {
        return when (stableIdMode) {
            StableIdMode.StableItem -> (items[position] as StableItem<*>).stableId.toLong()
            StableIdMode.StableLazyAccess -> (items[position] as StableLazyAccess<*>).stableId.toLong()
            else -> RecyclerView.NO_ID
        }
    }
}
