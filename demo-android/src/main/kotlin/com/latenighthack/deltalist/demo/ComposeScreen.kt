package com.latenighthack.deltalist.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.latenighthack.deltalist.android.DeltaLazyColumn

@Composable
fun ComposeScreen(viewModel: DemoViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val delta by viewModel.items.collectAsState(initial = Delta(emptyList(), Change.Reload))

    val selectedIndex = delta.items.indexOfFirst { it.id == selectedId }

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

        DeltaLazyColumn(
            deltaFlow = viewModel.items,
            modifier = Modifier.weight(1f),
            key = { it.id }
        ) { item ->
            ItemCard(
                item = item,
                isSelected = item.id == selectedId,
                onClick = {
                    selectedId = if (selectedId == item.id) null else item.id
                }
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

@Composable
private fun ItemCard(
    item: Item,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.background(Color.Blue.copy(alpha = 0.2f))
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = item.title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
