package com.latenighthack.deltalist.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.latenighthack.deltalist.softLoadedItems
import com.latenighthack.deltalist.android.compose.collectAsDeltaState
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.android.notifications.DeltaNotifier
import com.latenighthack.deltalist.android.notifications.notifier
import com.latenighthack.deltalist.demo.ui.theme.DeltaListDemoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationsActivity : ComponentActivity() {

    private val viewModel = NotificationsViewModel()
    private lateinit var notifier: DeltaNotifier<StableItem<DownloadVm>, DownloadVm>

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestPermission()

        notifier = viewModel.stableDownloads.notifier(this) {
            channel("downloads", "Downloads", NotificationManagerCompat.IMPORTANCE_LOW)
            idSpace(tag = "downloads", idBase = 1000)

            group("downloads-group") { items ->
                summaryNotification {
                    setSmallIcon(android.R.drawable.stat_sys_download)
                    setContentTitle("${items.size} downloads")
                    setContentText("In progress")
                }
            }

            itemState(accessor = { it.progress }, initial = { it.progress.value })

            content { dl ->
                val pct = (state as? Int) ?: 0
                val done = pct >= 100
                notification {
                    setSmallIcon(
                        if (done) android.R.drawable.stat_sys_download_done
                        else android.R.drawable.stat_sys_download
                    )
                    setContentTitle(dl.fileName)
                    setContentText(if (done) "Complete" else "$pct%")
                    setProgress(100, pct, false)
                    setOngoing(!done)
                    if (!done) action(android.R.drawable.ic_media_pause, "Pause", key = "pause")
                    action(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", key = "cancel")
                }
            }

            onContentTap { dl -> toast("Tapped ${dl.fileName}") }
            onAction { dl, key ->
                toast("$key: ${dl.fileName}")
                if (key == "cancel") viewModel.removeById(dl.id)
            }
            onDismiss { dl -> viewModel.removeById(dl.id) }
        }.bind(this)

        // Auto-advance progress so itemState re-notifies live, without any list mutation.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(800)
                    viewModel.tickAll()
                }
            }
        }

        setContent {
            DeltaListDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NotificationsScreen(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        notifier.unbind()
        super.onDestroy()
    }

    private fun maybeRequestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun NotificationsScreen(viewModel: NotificationsViewModel) {
    val delta = viewModel.downloads.collectAsDeltaState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Notifications Demo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Each download is a tray notification. Progress updates live via itemState; " +
                "swipe-to-dismiss and the action buttons route back through lambdas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.add() }) { Text("Add") }
            OutlinedButton(onClick = { viewModel.tickAll() }) { Text("Tick") }
            OutlinedButton(onClick = { viewModel.clear() }) { Text("Clear") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text("Active downloads: ${delta.items.size}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(delta.items.softLoadedItems(), key = { it.id }) { dl ->
                Text(dl.fileName, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
