package com.latenighthack.deltalist.android

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaFlow

@Composable
fun <T> DeltaLazyColumn(
    deltaFlow: DeltaFlow<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    content: @Composable LazyItemScope.(T) -> Unit
) {
    val delta by deltaFlow.collectAsState(initial = Delta(emptyList(), Change.Reload))

    LazyColumn(modifier = modifier) {
        items(
            items = delta.items,
            key = key
        ) { item ->
            content(item)
        }
    }
}

@Composable
fun <T> DeltaLazyColumn(
    deltaFlow: DeltaFlow<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    contentType: (T) -> Any? = { null },
    content: @Composable LazyItemScope.(T) -> Unit
) {
    val delta by deltaFlow.collectAsState(initial = Delta(emptyList(), Change.Reload))

    LazyColumn(modifier = modifier) {
        items(
            items = delta.items,
            key = key,
            contentType = contentType
        ) { item ->
            content(item)
        }
    }
}
