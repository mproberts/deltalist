package com.latenighthack.deltalist

/**
 * Represents a page of items fetched from a paginated data source.
 *
 * @param T The type of items in the page
 * @param U The type of the pagination token
 * @property items The list of items in this page
 * @property beforeToken Token to fetch the previous page, or null if this is the first page
 * @property afterToken Token to fetch the next page, or null if this is the last page
 * @property estimatedTotalSize Optional estimated total size of the entire paginated dataset
 */
data class Page<T, U>(
    val items: List<T>,
    val beforeToken: U?,
    val afterToken: U?,
    val estimatedTotalSize: Int? = null
)
