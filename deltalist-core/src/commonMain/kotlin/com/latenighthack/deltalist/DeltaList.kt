package com.latenighthack.deltalist

import kotlinx.coroutines.flow.Flow

typealias DeltaList<T> = Flow<Delta<T>>
