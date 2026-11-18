package com.breakpro.ultra.exportlog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(ExportLog.list(ctx)) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Export Manager") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = {
                TextButton(enabled = entries.isNotEmpty(), onClick = { ExportLog.clear(ctx); entries = emptyList() }) {
                    Text("Clear")
                }
            })
    }) { pad ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(pad).padding(16.dp)) { Text("No exports yet. Finished exports appear here.") }
        } else LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("History only: finished exports are listed, but the files live in Movies / Music / Pictures / " +
                    "Documents under 'BreakPro'. A real render queue is not implemented yet.",
                    style = MaterialTheme.typography.bodySmall)
            }
            items(entries) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${e.kind}  •  ${fmt.format(Date(e.time))}", style = MaterialTheme.typography.titleSmall)
                        val size = if (e.sizeBytes > 0) " • ${e.sizeBytes / 1024} KB" else ""
                        Text(e.detail + size, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
