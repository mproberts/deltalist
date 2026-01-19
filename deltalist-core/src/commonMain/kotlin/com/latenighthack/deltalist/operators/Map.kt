package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import kotlinx.coroutines.flow.map

fun <T, R> DeltaFlow<T>.mapItems(transform: (T) -> R): DeltaFlow<R> = map { delta ->
    Delta(
        items = delta.items.map(transform),
        change = delta.change
    )
}
