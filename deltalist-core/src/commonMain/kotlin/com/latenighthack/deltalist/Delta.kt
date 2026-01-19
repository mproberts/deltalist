package com.latenighthack.deltalist

data class Delta<T>(
    val items: List<T>,
    val change: Change
)
