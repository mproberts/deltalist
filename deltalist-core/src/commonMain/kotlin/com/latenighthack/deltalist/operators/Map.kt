package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import kotlinx.coroutines.flow.map

/**
 * Lazy list that transforms items on access.
 * Propagates soft access (load state) from the source.
 */
internal class MappedList<T, R>(
    private val source: SoftList<T>,
    private val transform: (T) -> R
) : AbstractSoftList<R>() {
    override val size: Int get() = source.size

    override fun softGet(index: Int): SoftValue<R>? =
        when (val soft = source.softGet(index)) {
            is SoftValue.Present -> SoftValue.Present(transform(soft.value))
            is SoftValue.NotLoaded -> soft
            null -> null
        }
}

fun <T, R> DeltaList<T>.mapItems(transform: (T) -> R): DeltaList<R> = map { delta ->
    Delta(
        items = MappedList(delta.items, transform),
        change = delta.change
    )
}
