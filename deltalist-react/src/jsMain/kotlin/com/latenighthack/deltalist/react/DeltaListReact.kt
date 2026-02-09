package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.DeltaList
import react.useEffect
import react.useState

public fun <T> useDeltaList(deltaList: DeltaList<T>, initial: List<T> = emptyList()): List<T> {
    var items by useState(initial)

    useEffect(deltaList) {
        deltaList.collect { delta ->
            items = delta.items
        }
    }

    return items
}
