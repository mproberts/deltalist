package com.latenighthack.deltalist.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta

@Composable
fun PaginatedListScreen(viewModel: DemoViewModel) {
    // Collect to trigger recomposition on changes
    val delta by viewModel.paginatedNumbers.collectAsState(initial = Delta(emptyList(), Change.Reload))
    val loadingDirection by viewModel.paginatedLoadingDirection.collectAsState()
    val loadedCount by viewModel.paginatedLoadedCount.collectAsState()
    val isLoading = loadingDirection != null
    val estimatedSize = 10_000 // We know the total size

    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar showing loaded vs estimated size
        PaginatedStatusBar(
            loadedSize = loadedCount,
            reportedSize = estimatedSize,
            isLoading = isLoading
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Use loadedCount for item count (from ViewModel)
            // Access through delta.items to trigger fetches near boundaries (wrapper handles this)
            items(
                count = loadedCount,
                key = { index -> index }
            ) { index ->
                // Access through delta.items triggers fetch logic when near boundaries
                val number = delta.items[index]
                NumberItemCard(
                    number = number,
                    index = index,
                    isLoaded = true
                )
            }

            // Loading indicator at the bottom when fetching
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun PaginatedStatusBar(
    loadedSize: Int,
    reportedSize: Int,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Paginated List Demo (10,000 items)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Loaded: $loadedSize / Estimated: $reportedSize",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun NumberItemCard(
    number: Int,
    index: Int,
    isLoaded: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$number",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "index: $index",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
