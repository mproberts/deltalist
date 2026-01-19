package com.latenighthack.deltalist

sealed class Change {
    data object Reload : Change()
    data class Mutations(val operations: List<Mutation>) : Change() {
        constructor(single: Mutation) : this(listOf(single))
        constructor(vararg mutations: Mutation) : this(mutations.toList())
    }
}
