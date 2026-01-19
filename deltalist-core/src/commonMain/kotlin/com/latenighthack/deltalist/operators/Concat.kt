package com.latenighthack.deltalist.operators

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

fun <T> DeltaFlow<T>.concat(other: DeltaFlow<T>): DeltaFlow<T> = combine(this, other) { first, second ->
    val combinedItems = first.items + second.items

    val change = when {
        first.change is Change.Reload || second.change is Change.Reload -> Change.Reload
        first.change is Change.Mutations && second.change is Change.Mutations -> {
            val firstMutations = (first.change as Change.Mutations).operations
            val secondMutations = (second.change as Change.Mutations).operations.map { mutation ->
                when (mutation) {
                    is Mutation.Insert -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Remove -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Update -> mutation.copy(index = mutation.index + first.items.size)
                    is Mutation.Move -> mutation.copy(
                        fromIndex = mutation.fromIndex + first.items.size,
                        toIndex = mutation.toIndex + first.items.size
                    )
                }
            }
            Change.Mutations(firstMutations + secondMutations)
        }
        else -> Change.Reload
    }

    Delta(combinedItems, change)
}

fun <T> DeltaFlow<T>.header(item: T): DeltaFlow<T> {
    val headerFlow: DeltaFlow<T> = flowOf(Delta(listOf(item), Change.Reload))
    return headerFlow.concat(this)
}

fun <T> DeltaFlow<T>.footer(item: T): DeltaFlow<T> {
    val footerFlow: DeltaFlow<T> = flowOf(Delta(listOf(item), Change.Reload))
    return this.concat(footerFlow)
}
