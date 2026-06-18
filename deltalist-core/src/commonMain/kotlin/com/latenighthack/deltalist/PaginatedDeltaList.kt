package com.latenighthack.deltalist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Direction of a pagination fetch operation.
 */
enum class LoadDirection {
    /** Initial load when the list is first accessed */
    INITIAL,
    /** Loading items before the current start of the list */
    BEFORE,
    /** Loading items after the current end of the list */
    AFTER
}

/**
 * Creates a paginated [DeltaList] that lazily fetches pages of data as items near
 * the boundaries of the loaded data are accessed.
 *
 * @param T The type of items in the list
 * @param U The type of pagination tokens (internal, not exposed)
 * @param scope The coroutine scope used for background fetch operations
 * @param fetchWindowSize The number of items from each boundary that triggers a fetch.
 *        When accessing an item within this distance from the start or end of the loaded
 *        items, a fetch will be triggered if there's more data available.
 * @param startToken The initial token to use for the first fetch
 * @param fetch The suspend function that fetches a page. Receives the load direction
 *        and the token for that direction. The closure can use the direction to manage
 *        its own loading state (e.g., emit to a separate loading flow).
 */
fun <T, U> paginatedDeltaList(
    scope: CoroutineScope,
    fetchWindowSize: Int = 1,
    startToken: U,
    fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
): DeltaList<T> = PaginatedDeltaListImpl(scope, fetchWindowSize, startToken, fetch)

internal class PaginatedDeltaListImpl<T, U>(
    private val scope: CoroutineScope,
    private val fetchWindowSize: Int,
    private val startToken: U,
    private val fetch: suspend (direction: LoadDirection, token: U) -> Page<T, U>
) : DeltaList<T> {

    private val mutex = Mutex()

    // Internal state
    private val _items = mutableListOf<T>()
    private var _beforeToken: U? = null
    private var _afterToken: U? = null
    private var _estimatedTotalSize: Int? = null
    private var _isLoadingBefore = false
    private var _isLoadingAfter = false
    private var _initialLoadDone = false

    private val state = MutableStateFlow<Delta<T>>(
        Delta(createWrapper(), Change.Reload)
    )

    // Bumped per emitted snapshot. The fetch-trigger closures below capture the generation
    // they were created in and no-op once superseded, so a stale snapshot's request() can't
    // drive a fetch (decision A: side effects honored only on the current snapshot).
    private var generation = 0

    private fun createWrapper(): PaginatedListWrapper<T> {
        generation += 1
        val myGen = generation
        return PaginatedListWrapper(
            items = _items.toList(),
            estimatedTotalSize = _estimatedTotalSize,
            fetchWindowSize = fetchWindowSize,
            hasMoreBefore = _beforeToken != null,
            hasMoreAfter = _afterToken != null || !_initialLoadDone,
            onAccessNearStart = { if (myGen == generation) triggerBeforeFetch() },
            onAccessNearEnd = { if (myGen == generation) triggerAfterFetch() },
            onAccessWhenEmpty = { if (myGen == generation) triggerInitialFetch() }
        )
    }

    private fun triggerInitialFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_initialLoadDone || _isLoadingAfter) return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_initialLoadDone || _isLoadingAfter) return@launch
                _isLoadingAfter = true
            }

            try {
                val page = fetch(LoadDirection.INITIAL, startToken)

                mutex.withLock {
                    _items.addAll(page.items)
                    _beforeToken = page.beforeToken
                    _afterToken = page.afterToken
                    _estimatedTotalSize = page.estimatedTotalSize
                    _initialLoadDone = true
                    _isLoadingAfter = false

                    emitChange(page.items.size, isAppend = true, isInitial = true)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingAfter = false
                }
                throw e
            }
        }
    }

    private fun triggerBeforeFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_isLoadingBefore) return
        val token = _beforeToken ?: return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_isLoadingBefore || _beforeToken == null) return@launch
                _isLoadingBefore = true
            }

            try {
                val page = fetch(LoadDirection.BEFORE, token)

                mutex.withLock {
                    val insertCount = page.items.size
                    _items.addAll(0, page.items)
                    _beforeToken = page.beforeToken

                    if (page.estimatedTotalSize != null) {
                        _estimatedTotalSize = page.estimatedTotalSize
                    }

                    _isLoadingBefore = false

                    emitChange(insertCount, isAppend = false, isInitial = false)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingBefore = false
                }
                throw e
            }
        }
    }

    private fun triggerAfterFetch() {
        // Early check to avoid launching unnecessary coroutines
        if (_isLoadingAfter) return
        val token = _afterToken ?: return

        scope.launch {
            mutex.withLock {
                // Double-check under lock for thread safety
                if (_isLoadingAfter || _afterToken == null) return@launch
                _isLoadingAfter = true
            }

            try {
                val page = fetch(LoadDirection.AFTER, token)

                mutex.withLock {
                    val previousSize = _items.size
                    val insertCount = page.items.size
                    _items.addAll(page.items)
                    _afterToken = page.afterToken

                    if (page.estimatedTotalSize != null) {
                        _estimatedTotalSize = page.estimatedTotalSize
                    }

                    _isLoadingAfter = false

                    emitChange(insertCount, isAppend = true, isInitial = false, previousRealSize = previousSize)
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _isLoadingAfter = false
                }
                throw e
            }
        }
    }

    // The display size and leading-placeholder count the last emitted Delta reported.
    private var _lastDisplaySize = 0
    private var _lastLeading = 0

    private fun emitChange(count: Int, isAppend: Boolean, isInitial: Boolean, previousRealSize: Int = 0) {
        val currentList = createWrapper()
        val newDisplaySize = currentList.size
        val leadingNow = if (_beforeToken != null) 1 else 0

        if (isInitial) {
            _lastDisplaySize = newDisplaySize
            _lastLeading = leadingNow
            state.value = Delta(currentList, Change.Reload)
            return
        }

        // Nothing changed structurally (e.g. an empty page that didn't toggle a placeholder).
        if (count == 0 && newDisplaySize == _lastDisplaySize && leadingNow == _lastLeading) return

        val oldDisplaySize = _lastDisplaySize
        val mutations = mutableListOf<Mutation>()
        var working = oldDisplaySize

        if (isAppend) {
            // New items land just after the leading placeholder + previously-loaded items.
            val insertIndex = leadingNow + previousRealSize
            val coveredEnd = minOf(insertIndex + count, oldDisplaySize)
            val coveredCount = (coveredEnd - insertIndex).coerceAtLeast(0)
            if (coveredCount > 0) {
                mutations.add(Mutation.Update(insertIndex, coveredCount))
            }
            val insertedBeyond = count - coveredCount
            if (insertedBeyond > 0) {
                mutations.add(Mutation.Insert(oldDisplaySize, insertedBeyond))
                working += insertedBeyond
            }
        } else {
            // Before-fetch: prepend `count` real items, accounting for the leading
            // placeholder transition (it persists if earlier pages still remain, otherwise
            // the first new item fills the old placeholder slot).
            val l0 = _lastLeading
            val l1 = leadingNow
            when {
                l0 == 1 && l1 == 1 -> if (count > 0) {
                    mutations.add(Mutation.Insert(1, count)); working += count
                }
                l0 == 1 && l1 == 0 -> if (count >= 1) {
                    mutations.add(Mutation.Update(0, 1))
                    if (count > 1) mutations.add(Mutation.Insert(1, count - 1))
                    working += count - 1
                } else {
                    mutations.add(Mutation.Remove(0, 1)); working -= 1
                }
                else -> {
                    if (count > 0) { mutations.add(Mutation.Insert(0, count)); working += count }
                    if (l1 == 1) { mutations.add(Mutation.Insert(0, 1)); working += 1 }
                }
            }
        }

        // Reconcile remaining placeholder delta at the trailing end: shrink removes phantoms
        // a short last page revealed; growth adds placeholders from a larger estimate.
        if (working > newDisplaySize) {
            mutations.add(Mutation.Remove(newDisplaySize, working - newDisplaySize))
        } else if (working < newDisplaySize) {
            mutations.add(Mutation.Insert(working, newDisplaySize - working))
        }

        _lastDisplaySize = newDisplaySize
        _lastLeading = leadingNow
        if (mutations.isEmpty()) return
        state.value = Delta(currentList, Change.Mutations(mutations))
    }

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Delta<T>>) {
        // Trigger initial fetch when collection starts
        if (!_initialLoadDone && _items.isEmpty()) {
            triggerInitialFetch()
        }
        state.collect(collector)
    }
}

/**
 * A list wrapper that intercepts access to trigger pagination fetches and reports estimated size.
 * Implements [SoftList] to allow operators to inspect values without triggering fetches.
 */
internal class PaginatedListWrapper<T>(
    private val items: List<T>,
    private val estimatedTotalSize: Int?,
    private val fetchWindowSize: Int,
    private val hasMoreBefore: Boolean,
    private val hasMoreAfter: Boolean,
    private val onAccessNearStart: () -> Unit,
    private val onAccessNearEnd: () -> Unit,
    private val onAccessWhenEmpty: () -> Unit
) : AbstractSoftList<T>() {

    // A single leading placeholder when earlier pages remain, so the UI has a "load
    // earlier" NotLoaded slot at the top whose request() drives the before-fetch
    // (symmetric with the trailing placeholder).
    private val leading: Int get() = if (hasMoreBefore) 1 else 0

    private val trailing: Int
        get() {
            val realSize = items.size
            val estimated = estimatedTotalSize
            return when {
                // While more remains, inflate to the estimate if it's larger...
                hasMoreAfter && estimated != null && estimated > realSize -> estimated - realSize
                // ...otherwise a single trailing placeholder so the UI always has a NotLoaded
                // slot whose request() drives the next fetch (no more throw-to-fetch). Once
                // exhausted, no trailing placeholder.
                hasMoreAfter -> 1
                else -> 0
            }
        }

    override val size: Int get() = leading + items.size + trailing

    override fun softGet(index: Int): SoftValue<T>? {
        // Pure peek (no fetch side effects). Bounds use the gated [size]. The trigger
        // closures are epoch-guarded by the producer, so a superseded snapshot's request()
        // is a safe no-op.
        if (index < 0 || index >= size) return null

        if (index < leading) {
            // Leading "load earlier" placeholder.
            return SoftValue.NotLoaded { if (hasMoreBefore) onAccessNearStart() }
        }

        val realIndex = index - leading
        if (realIndex < items.size) {
            return SoftValue.Present(items[realIndex])
        }

        // Trailing "load more" placeholder.
        return SoftValue.NotLoaded { if (hasMoreAfter) onAccessNearEnd() }
    }
}
