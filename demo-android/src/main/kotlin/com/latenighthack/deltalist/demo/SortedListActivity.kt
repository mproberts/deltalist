package com.latenighthack.deltalist.demo

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.softLoadedItems
import com.latenighthack.deltalist.android.compose.collectAsDeltaState
import com.latenighthack.deltalist.android.recyclerview.DeltaAdapter
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme

private const val GRID_COLUMNS = 4

class SortedListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SortedListScreen()
                }
            }
        }
    }
}

@Composable
private fun SortedListScreen() {
    val viewModel = remember { SortedListViewModel() }
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
                0 -> SortedComposeContent(viewModel)
                1 -> SortedRecyclerViewContent(viewModel)
            }
        }
    }
}

@Composable
private fun SortedComposeContent(viewModel: SortedListViewModel) {
    val delta = viewModel.profiles.collectAsDeltaState()
    val profiles = delta.items.softLoadedItems()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(items = profiles, key = { it.id }) { profile ->
                ProfileCell(profile) { viewModel.remove(profile) }
            }
        }

        AddBar(onAdd = { viewModel.addRandom() })
    }
}

@Composable
private fun ProfileCell(profile: Profile, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = profile.firstName,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = profile.lastName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SortedRecyclerViewContent(viewModel: SortedListViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutManager = GridLayoutManager(context, GRID_COLUMNS)
                        adapter = ProfileAdapter(viewModel.profiles) { profile ->
                            viewModel.remove(profile)
                        }.also { adapter ->
                            adapter.bind(lifecycleOwner)
                        }
                    }
                }
            )
        }

        AddBar(onAdd = { viewModel.addRandom() })
    }
}

@Composable
private fun AddBar(onAdd: () -> Unit) {
    Button(
        onClick = onAdd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text("Add")
    }
}

// Basic DeltaAdapter rendering profiles into a grid; tapping a cell removes the profile.
private class ProfileAdapter(
    deltaList: DeltaList<Profile>,
    private val onClick: (Profile) -> Unit
) : DeltaAdapter<Profile, ProfileAdapter.ProfileViewHolder>(deltaList) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 48, 16, 48)
        }
        val firstView = TextView(parent.context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val lastView = TextView(parent.context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(firstView)
        layout.addView(lastView)
        return ProfileViewHolder(layout, firstView, lastView)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position)
        holder.firstView.text = profile.firstName
        holder.lastView.text = profile.lastName
        holder.itemView.setOnClickListener { onClick(profile) }
    }

    class ProfileViewHolder(
        view: View,
        val firstView: TextView,
        val lastView: TextView
    ) : RecyclerView.ViewHolder(view)
}
