package com.latenighthack.deltalist

/**
 * Canonical reference applier for [Change].
 *
 * This defines the *contract* that every platform binding must honor: a
 * [Change.Mutations] is a list of operations applied **sequentially**, each one in
 * the coordinate space of the running list (i.e. after all prior operations in the
 * same change have been applied). [Mutation.Move] indices are likewise running
 * coordinates and always describe a single item ([Mutation.Move.count] == 1 as
 * emitted by [com.latenighthack.deltalist.operators.asDeltaList]).
 *
 * Inserted and updated values are sourced from [newItems] at the operation's index.
 * [com.latenighthack.deltalist.operators.asDeltaList] emits operations left-to-right
 * so that, at the moment each operation runs, the running list's prefix already
 * matches [newItems] up to that index — which makes this sourcing exact.
 *
 * This is intentionally strict (it will throw on an out-of-range index) so that the
 * diff oracle surfaces any contract violation instead of silently masking it.
 */
fun <T> applyChange(old: List<T>, change: Change, newItems: List<T>): List<T> = when (change) {
    is Change.Reload -> newItems
    is Change.Mutations -> {
        val working = old.toMutableList()
        for (op in change.operations) {
            when (op) {
                is Mutation.Insert -> for (i in 0 until op.count) {
                    working.add(op.index + i, newItems[op.index + i])
                }
                is Mutation.Remove -> repeat(op.count) {
                    working.removeAt(op.index)
                }
                is Mutation.Update -> for (i in 0 until op.count) {
                    working[op.index + i] = newItems[op.index + i]
                }
                is Mutation.Move -> repeat(op.count) { k ->
                    val item = working.removeAt(op.fromIndex + k)
                    working.add(op.toIndex + k, item)
                }
            }
        }
        working
    }
}

/** Convenience overload that applies a whole [Delta] to [old]. */
fun <T> applyChange(old: List<T>, delta: Delta<T>): List<T> =
    applyChange(old, delta.change, delta.items.softLoadedItems())
