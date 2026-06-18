package com.latenighthack.deltalist

/**
 * A section containing header data and a list of items.
 *
 * @param S The section header type
 * @param T The item type within the section
 */
data class Section<out S, out T>(
    val header: S,
    val items: SoftList<T>
) {
    /** Convenience on-ramp: a plain (fully-loaded) list is wrapped as a [SoftList]. */
    constructor(header: S, items: List<T>) : this(header, items.asSoftList())
}
