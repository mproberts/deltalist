package com.latenighthack.deltalist

/**
 * A delta emission for sectioned lists.
 * Contains the current sections state and how it changed.
 */
data class SectionedDelta<out S, out T>(
    val sections: List<Section<S, T>>,
    val change: SectionedChange
)

/**
 * Describes how a sectioned list changed between emissions.
 */
sealed class SectionedChange {
    /**
     * Complete reload - all sections replaced.
     */
    data object Reload : SectionedChange()

    /**
     * Section-level mutations (add/remove/move/update sections).
     * Update means the section header changed.
     */
    data class Sections(val mutations: List<SectionMutation>) : SectionedChange() {
        constructor(single: SectionMutation) : this(listOf(single))
        constructor(vararg mutations: SectionMutation) : this(mutations.toList())
    }

    /**
     * Item-level mutations within a specific section.
     * Uses the standard Mutation type.
     */
    data class Items(val section: Int, val mutations: List<Mutation>) : SectionedChange() {
        constructor(section: Int, single: Mutation) : this(section, listOf(single))
        constructor(section: Int, vararg mutations: Mutation) : this(section, mutations.toList())
    }
}

/**
 * Mutations that affect section structure.
 */
sealed class SectionMutation {
    /** Insert count sections starting at index */
    data class Insert(val index: Int, val count: Int = 1) : SectionMutation()

    /** Remove count sections starting at index */
    data class Remove(val index: Int, val count: Int = 1) : SectionMutation()

    /** Move a section from one index to another */
    data class Move(val fromIndex: Int, val toIndex: Int) : SectionMutation()

    /** Section header changed at index */
    data class Update(val index: Int) : SectionMutation()
}
