package com.latenighthack.deltalist

/**
 * A section containing header data and a list of items.
 *
 * @param S The section header type
 * @param T The item type within the section
 */
data class Section<out S, out T>(
    val header: S,
    val items: List<T>
)
