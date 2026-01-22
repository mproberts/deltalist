package com.latenighthack.deltalist

/**
 * Represents a value that may or may not be loaded yet.
 */
sealed class SoftValue<out T> {
    /**
     * The value is present and loaded.
     */
    data class Present<T>(val value: T) : SoftValue<T>()

    /**
     * The value is within the expected bounds but not yet loaded.
     * This typically occurs with paginated lists where the estimated size
     * is larger than the currently loaded items.
     *
     * Call [request] to trigger a fetch for this value if one is available.
     */
    class NotLoaded(private val onRequest: (() -> Unit)? = null) : SoftValue<Nothing>() {
        /**
         * Requests that the value be loaded. This will trigger the appropriate
         * fetch operation if one is available. Has no effect if no fetch
         * operation is associated with this NotLoaded instance.
         */
        fun request() {
            onRequest?.invoke()
        }

        // All NotLoaded instances are equal regardless of callback
        override fun equals(other: Any?): Boolean = other is NotLoaded
        override fun hashCode(): Int = NotLoaded::class.hashCode()
        override fun toString(): String = "NotLoaded"
    }
}

/**
 * A list that supports "soft" access to elements without triggering side effects.
 *
 * Regular [get] access may trigger side effects like pagination fetches when
 * accessing items near boundaries. [softGet] allows inspecting whether a value
 * exists without triggering these side effects.
 *
 * This is useful for operators like filter or map that need to iterate over
 * items without inadvertently triggering fetches for unloaded data.
 */
interface SoftList<out T> : List<T> {
    /**
     * Gets the value at the index without triggering any side effects.
     *
     * @param index The index to access
     * @return `null` if the index is out of bounds (negative or >= size),
     *         [SoftValue.NotLoaded] if the index is within bounds but the value
     *         is not yet loaded, or [SoftValue.Present] containing the value
     *         if it is loaded.
     */
    fun softGet(index: Int): SoftValue<T>?
}

/**
 * Extension to safely get a value from any list, returning a [SoftValue].
 * For regular lists, this will return [SoftValue.Present] or null.
 * For [SoftList] implementations, this delegates to [SoftList.softGet].
 */
fun <T> List<T>.softGetOrNull(index: Int): SoftValue<T>? {
    return if (this is SoftList<T>) {
        softGet(index)
    } else {
        if (index < 0 || index >= size) null
        else SoftValue.Present(get(index))
    }
}

/**
 * Gets the size of a list safely.
 * This is useful for iOS where accessing .count on a bridged list can trigger iteration.
 */
fun <T> List<T>.softSize(): Int = size

/**
 * Gets the number of actually loaded items in a list.
 * For regular lists, this equals the size.
 * For [SoftList] implementations, this returns the count of items that are [SoftValue.Present].
 */
fun <T> List<T>.softLoadedCount(): Int {
    if (this !is SoftList<T>) return size

    var count = 0
    for (i in indices) {
        when (softGet(i)) {
            is SoftValue.Present -> count++
            is SoftValue.NotLoaded -> break // Loaded items are contiguous at the start
            null -> break
        }
    }
    return count
}

/**
 * Maps over only the loaded items in a list, returning a new list.
 * For regular lists, this maps over all items.
 * For [SoftList] implementations, this only maps over items that are [SoftValue.Present].
 *
 * This is safe to call from iOS without triggering bridging issues.
 */
fun <T, R> List<T>.softMapLoaded(transform: (T) -> R): List<R> {
    val result = mutableListOf<R>()

    if (this is SoftList<T>) {
        for (i in indices) {
            when (val soft = softGet(i)) {
                is SoftValue.Present -> result.add(transform(soft.value))
                is SoftValue.NotLoaded -> break // Stop at first unloaded
                null -> break
            }
        }
    } else {
        for (item in this) {
            result.add(transform(item))
        }
    }

    return result
}

/**
 * Iterates over only the loaded items in a list.
 * For regular lists, this iterates over all items.
 * For [SoftList] implementations, this only iterates over items that are [SoftValue.Present].
 *
 * This is safe to call from iOS without triggering bridging issues.
 */
inline fun <T> List<T>.softForEachLoaded(action: (T) -> Unit) {
    if (this is SoftList<T>) {
        for (i in indices) {
            when (val soft = softGet(i)) {
                is SoftValue.Present -> action(soft.value)
                is SoftValue.NotLoaded -> break
                null -> break
            }
        }
    } else {
        for (item in this) {
            action(item)
        }
    }
}

/**
 * Returns a list containing only the loaded items.
 * For regular lists, returns a copy of the list.
 * For [SoftList] implementations, returns only items that are [SoftValue.Present].
 *
 * This is safe to call from iOS without triggering bridging issues.
 */
fun <T> List<T>.softLoadedItems(): List<T> {
    if (this !is SoftList<T>) return this.toList()

    val result = mutableListOf<T>()
    for (i in indices) {
        when (val soft = softGet(i)) {
            is SoftValue.Present -> result.add(soft.value)
            is SoftValue.NotLoaded -> break
            null -> break
        }
    }
    return result
}

// ============================================================================
// Delta helpers - these take Delta directly to avoid iOS bridging issues
// when accessing delta.items from Swift
// ============================================================================

/**
 * Returns a list containing only the loaded items from a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.loadedItems(): List<T> = items.softLoadedItems()

/**
 * Returns the count of loaded items in a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T> Delta<T>.loadedCount(): Int = items.softLoadedCount()

/**
 * Maps over only the loaded items in a Delta.
 * This is safe to call from iOS - pass the Delta directly to avoid bridging issues.
 */
fun <T, R> Delta<T>.mapLoaded(transform: (T) -> R): List<R> = items.softMapLoaded(transform)
