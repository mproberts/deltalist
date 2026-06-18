package com.latenighthack.deltalist

/**
 * Test-only conveniences. In production [SoftList] is intentionally NOT a [List] (no
 * `get`/iteration/`==`), but tests routinely build fully-loaded snapshots and want to read
 * them List-style. These extensions live only in `commonTest`, so they don't weaken the
 * shipped contract.
 */

operator fun <T> SoftList<T>.get(index: Int): T {
    // Mirror the old List.get semantics tests relied on: for a LazyList, indexing acquires
    // (pins) the item; for a plain soft snapshot it's a peek.
    val v = if (this is LazyList<T>) acquire(index) else softGet(index)
    return when (v) {
        is SoftValue.Present -> v.value
        else -> throw IndexOutOfBoundsException("index $index is not loaded / out of range (size=$size)")
    }
}

operator fun <T> SoftList<T>.iterator(): Iterator<T> = softLoadedItems().iterator()

fun <T> SoftList<T>.toList(): List<T> = softLoadedItems()

fun <T> SoftList<T>.isEmpty(): Boolean = size == 0

fun <T> SoftList<T>.isNotEmpty(): Boolean = size > 0

val <T> SoftList<T>.indices: IntRange get() = 0 until size

fun <T, R> SoftList<T>.map(transform: (T) -> R): List<R> = softLoadedItems().map(transform)

fun <T> SoftList<T>.filter(predicate: (T) -> Boolean): List<T> = softLoadedItems().filter(predicate)

fun <T> SoftList<T>.forEach(action: (T) -> Unit) = softLoadedItems().forEach(action)

fun <T> SoftList<T>.forEachIndexed(action: (Int, T) -> Unit) = softLoadedItems().forEachIndexed(action)

fun <T> SoftList<T>.mapIndexed(transform: (Int, T) -> Any?): List<Any?> = softLoadedItems().mapIndexed(transform)

fun <T, R> SoftList<T>.mapNotNull(transform: (T) -> R?): List<R> = softLoadedItems().mapNotNull(transform)

fun <T> SoftList<T>.count(predicate: (T) -> Boolean): Int = softLoadedItems().count(predicate)

fun <T> SoftList<T>.first(): T = softLoadedItems().first()

fun <T> SoftList<T>.last(): T = softLoadedItems().last()

fun <T> SoftList<T>.contains(element: T): Boolean = softLoadedItems().contains(element)

fun <T> SoftList<T>.getOrNull(index: Int): T? = (softGet(index) as? SoftValue.Present)?.value

fun <T> SoftList<T>.subList(fromIndex: Int, toIndex: Int): List<T> =
    softLoadedItems().subList(fromIndex, toIndex)

fun <T> SoftList<T>.indexOf(element: T): Int = softLoadedItems().indexOf(element)

/** Structural comparison of a [SoftList]'s loaded contents against an ordinary list. */
fun <T> SoftList<T>.contentEquals(expected: List<T>): Boolean = softLoadedItems() == expected
