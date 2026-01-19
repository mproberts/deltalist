package com.latenighthack.deltalist.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaFlow
import com.latenighthack.deltalist.android.DeltaAdapter

@Composable
fun RecyclerViewScreen(viewModel: DemoViewModel) {
    var selectedIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = Modifier.fillMaxSize()) {
        ControlButtons(
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

        Box(modifier = Modifier.weight(1f)) {
            val lifecycleOwner = LocalLifecycleOwner.current

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = ItemAdapter(viewModel.items) { index ->
                            selectedIndex = if (selectedIndex == index) -1 else index
                        }.also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                },
                update = { recyclerView ->
                    (recyclerView.adapter as? ItemAdapter)?.selectedIndex = selectedIndex
                }
            )
        }
    }
}

@Composable
private fun ControlButtons(
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

private class ItemAdapter(
    deltaFlow: DeltaFlow<Item>,
    private val onItemClick: (Int) -> Unit
) : DeltaAdapter<Item, ItemAdapter.ItemViewHolder>(deltaFlow) {

    var selectedIndex: Int = -1
        set(value) {
            val oldIndex = field
            field = value
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (value >= 0) notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position == selectedIndex, onItemClick)
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(android.R.id.text1)

        fun bind(item: Item, isSelected: Boolean, onClick: (Int) -> Unit) {
            textView.text = item.title
            itemView.isActivated = isSelected
            itemView.setBackgroundColor(
                if (isSelected) 0x330000FF else 0x00000000
            )
            itemView.setOnClickListener { onClick(bindingAdapterPosition) }
        }
    }
}
