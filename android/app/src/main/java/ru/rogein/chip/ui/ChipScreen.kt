package ru.rogein.chip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.rogein.chip.queue.QueuedChipEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF1B4D3E)
private val Cream = Color(0xFFF4F1EA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipScreen(viewModel: ChipQueueViewModel) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Cream,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Малахит — чип") },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearQueue) {
                            Text("Очистить", color = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Green,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (items.isEmpty() && !busy) {
                Text(
                    text = "Очередь пуста. Данные чипа сохраняются здесь и позже уйдут на десктоп.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color(0xFF5C5C5C),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    QueueCard(item)
                }
            }
        }
    }
}

@Composable
private fun QueueCard(item: QueuedChipEntity) {
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.logicalId?.let { "Логический номер $it" } ?: "Без логического номера",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("${item.punchCount} отметок · ${item.firstTime ?: "—"} – ${item.lastTime ?: "—"}")
            Text("UID ${item.uid}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                "в очереди с ${timeFmt.format(Date(item.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}
