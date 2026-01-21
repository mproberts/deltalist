package com.latenighthack.deltalist.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.StableLazyAccess
import com.latenighthack.deltalist.android.compose.collectAsDeltaState
import com.latenighthack.deltalist.android.compose.rememberItemState
import com.latenighthack.deltalist.android.recyclerview.FlowDeltaAdapter
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme

class ListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ListScreen()
                }
            }
        }
    }
}

@Composable
private fun ListScreen() {
    val viewModel = remember { ListViewModel() }
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Compose") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("RecyclerView") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ListComposeContent(viewModel)
                1 -> ListRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun ListComposeContent(viewModel: ListViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val delta = viewModel.tickingItems.collectAsDeltaState()
    val originalDelta = viewModel.items.collectAsDeltaState()
    val selectedIndex = originalDelta.items.indexOfFirst { it.id == selectedId }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = delta.items,
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

        ListControlButtons(
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
    }
}

@Composable
private fun LazyTickingItemCard(
    stableLazyAccess: StableLazyAccess<TickingItem>,
    isSelected: (String) -> Boolean,
    onClick: (String) -> Unit
) {
    val stableId = stableLazyAccess.stableId
    val tickingItem = remember(stableId) { stableLazyAccess.getOrAcquire() }

    DisposableEffect(stableId) {
        onDispose {
            tickingItem.stop()
            stableLazyAccess.release()
        }
    }

    // Use rememberItemState for automatic flow lifecycle management
    val tickCount = rememberItemState(
        item = tickingItem,
        key = stableId,
        initialValue = 0
    ) { it.tickCount }

    val itemId = tickingItem.item.id
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
private fun ListRecyclerViewContent(viewModel: ListViewModel) {
    var selectedIndex by remember { mutableIntStateOf(-1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = TickingItemAdapter(viewModel.tickingItems) { index ->
                            selectedIndex = if (selectedIndex == index) -1 else index
                        }.also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                },
                update = { recyclerView ->
                    (recyclerView.adapter as? TickingItemAdapter)?.selectedIndex = selectedIndex
                }
            )
        }

        ListControlButtons(
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
                    selectedIndex = -1
                }
            } else null
        )
    }
}

@Composable
private fun ListControlButtons(
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

// RecyclerView Adapter using FlowDeltaAdapter
// FlowDeltaAdapter automatically handles flow lifecycle (start on attach, stop on detach)
// We use the lifecycle hooks to manage LazyAccess acquisition/release
private class TickingItemAdapter(
    deltaList: DeltaList<StableLazyAccess<TickingItem>>,
    private val onItemClick: (Int) -> Unit
) : FlowDeltaAdapter<StableLazyAccess<TickingItem>, Int, TickingItemAdapter.TickingItemViewHolder>(
    deltaList,
    flowAccessor = { stableLazyAccess -> stableLazyAccess.getOrAcquire().tickCount }
) {
    // Track acquired items to release them when flow stops
    private val acquiredItems = mutableMapOf<TickingItemViewHolder, Pair<StableLazyAccess<TickingItem>, TickingItem>>()

    var selectedIndex: Int = -1
        set(value) {
            val oldIndex = field
            field = value
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (value >= 0) notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickingItemViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
        }
        val titleView = TextView(parent.context).apply { textSize = 16f }
        val tickView = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(titleView)
        layout.addView(tickView)
        return TickingItemViewHolder(layout, titleView, tickView)
    }

    // Called on bind - set up static content only
    override fun onBindItem(holder: TickingItemViewHolder, position: Int, item: StableLazyAccess<TickingItem>) {
        val tickingItem = item.getOrAcquire()
        holder.titleView.text = tickingItem.item.title
        holder.tickView.text = "Ticks: ${tickingItem.tickCount.value} | StableId: ${item.stableId}"
        holder.stableId = item.stableId

        val isSelected = position == selectedIndex
        holder.itemView.isActivated = isSelected
        holder.itemView.setBackgroundColor(if (isSelected) 0x330000FF else 0x00000000)
        holder.itemView.setOnClickListener { onItemClick(holder.bindingAdapterPosition) }
    }

    // Called when flow emits - update dynamic content
    override fun onItemStateChanged(holder: TickingItemViewHolder, state: Int) {
        holder.tickView.text = "Ticks: $state | StableId: ${holder.stableId}"
    }

    // Called when flow starts (view attached) - track acquired item for later release
    override fun onItemFlowStarted(holder: TickingItemViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val stableLazyAccess = getItem(position)
            val tickingItem = stableLazyAccess.getOrAcquire()
            acquiredItems[holder] = stableLazyAccess to tickingItem
        }
    }

    // Called when flow stops (view detached/recycled) - release acquired item
    override fun onItemFlowStopped(holder: TickingItemViewHolder) {
        acquiredItems.remove(holder)?.let { (stableLazyAccess, tickingItem) ->
            tickingItem.stop()
            stableLazyAccess.release()
        }
    }

    class TickingItemViewHolder(
        view: View,
        val titleView: TextView,
        val tickView: TextView
    ) : RecyclerView.ViewHolder(view) {
        var stableId: Int = 0
    }
}
