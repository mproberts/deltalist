package com.latenighthack.deltalist.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.StableLazyAccess

@Composable
fun ComposeScreen(viewModel: DemoViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val delta by viewModel.tickingItems.collectAsState(initial = Delta(emptyList(), Change.Reload))

    // Find selected index in the original items list for operations
    val originalDelta by viewModel.items.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val selectedIndex = originalDelta.items.indexOfFirst { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize()) {
        ComposeControlButtons(
            onAdd = { viewModel.addItem() },
            onBatchAdd = { viewModel.batchAdd() },
            onClear = { viewModel.clear() },
            onInsertBefore = if (selectedIndex >= 0) {
                { viewModel.insertBefore(selectedIndex) }
            } else null,
            onInsertAfter = if (selectedIndex >= 0) {
                { viewModel.insertAfter(selectedIndex) }
            } else null,
            onRemove = if (selectedIndex >= 0) {
                {
                    viewModel.removeItem(selectedIndex)
                    selectedId = null
                }
            } else null
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = delta.items,
                // Use stableId for proper item tracking - this ID follows the item through mutations
                key = { stableLazyAccess -> stableLazyAccess.stableId }
            ) { stableLazyAccess ->
                LazyTickingItemCard(
                    stableLazyAccess = stableLazyAccess,
                    isSelected = { id -> id == selectedId },
                    onClick = { id ->
                        selectedId = if (selectedId == id) null else id
                    }
                )
            }
        }
    }
}

/**
 * A card that handles the StableLazyAccess lifecycle automatically.
 * Uses stableId for lifecycle management - the ID follows the item through mutations.
 */
@Composable
private fun LazyTickingItemCard(
    stableLazyAccess: StableLazyAccess<TickingItem>,
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit
) {
    // Acquire the item - getOrAcquire() is idempotent (returns cached value if exists)
    val tickingItem = stableLazyAccess.getOrAcquire()
    val itemId = tickingItem.item.id
    val stableId = stableLazyAccess.stableId

    // Use stableId as key for lifecycle management.
    // The stableId follows the item through mutations, so disposal only happens
    // when the item is actually removed from the list.
    DisposableEffect(stableId) {
        onDispose {
            tickingItem.stop()
            stableLazyAccess.release()
        }
    }

    // Observe the tick count
    val tickCount by tickingItem.tickCount.collectAsState()
    val isItemSelected = isSelected(itemId)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = { onClick(itemId) })
            .then(
                if (isItemSelected) {
                    Modifier.background(Color.Blue.copy(alpha = 0.2f))
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tickingItem.item.title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Ticks: $tickCount | StableId: $stableId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ComposeControlButtons(
    onAdd: () -> Unit,
    onBatchAdd: () -> Unit,
    onClear: () -> Unit,
    onInsertBefore: (() -> Unit)?,
    onInsertAfter: (() -> Unit)?,
    onRemove: (() -> Unit)?
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onAdd, modifier = Modifier.padding(4.dp)) {
                Text("Add")
            }
            Button(onClick = onBatchAdd, modifier = Modifier.padding(4.dp)) {
                Text("Batch Add")
            }
            Button(onClick = onClear, modifier = Modifier.padding(4.dp)) {
                Text("Clear")
            }
        }
        if (onInsertBefore != null || onInsertAfter != null || onRemove != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                onInsertBefore?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Insert Before")
                    }
                }
                onInsertAfter?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Insert After")
                    }
                }
                onRemove?.let {
                    Button(onClick = it, modifier = Modifier.padding(4.dp)) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}
