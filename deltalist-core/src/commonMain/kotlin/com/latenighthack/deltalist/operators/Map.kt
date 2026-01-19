package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import kotlinx.coroutines.flow.map

/**
 * Lazy list that transforms items on access.
 */
internal class MappedList<T, R>(
    private val source: List<T>,
    private val transform: (T) -> R
) : AbstractList<R>() {
    override val size: Int get() = source.size
    override fun get(index: Int): R = transform(source[index])
}

fun <T, R> DeltaFlow<T>.mapItems(transform: (T) -> R): DeltaFlow<R> = map { delta ->
    Delta(
        items = MappedList(delta.items, transform),
        change = delta.change
    )
}
