package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow

typealias DeltaFlow<T> = Flow<Delta<T>>
