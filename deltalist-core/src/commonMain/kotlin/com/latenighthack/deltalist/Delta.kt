package com.latenighthack.deltalist

data class Delta<T>(
    val items: SoftList<T>,
    val change: Change
) {
    /**
     * On-ramp constructor: a plain, already-loaded [List] is by definition fully loaded,
     * so it is wrapped as a [SoftList] whose every slot is present. This keeps producers
     * that compute a snapshot as a plain list ergonomic ("plain data in, soft only out").
     */
    constructor(items: List<T>, change: Change) : this(items.asSoftList(), change)
}
