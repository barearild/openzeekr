package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.SentryVideoDetail
import com.openzeekr.app.remote.CallResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SentryScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Query the last 24h of sentry events.") }
    var events by remember { mutableStateOf<List<SentryVideoDetail>>(emptyList()) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sentry / Sentinel", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cloud footage list is live against sentinel-monitoring-service. Live view needs the RTC provider SDK (appId) which is not identified yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                status = "Loading events…"
                scope.launch {
                    val end = System.currentTimeMillis()
                    val start = end - 24L * 3600 * 1000
                    when (val r = deps.sentry.events(start, end)) {
                        is CallResult.Ok -> { events = r.value; status = "${r.value.size} events" }
                        is CallResult.Err -> status = "✗ ${r.message}"
                    }
                }
            }) { Text("Load last 24h") }

            OutlinedButton(onClick = {
                status = "Requesting live token…"
                scope.launch {
                    status = when (val r = deps.sentry.liveToken(roomId = deps.config.current().vin)) {
                        is CallResult.Ok -> "Live token ✓ (needs RTC SDK to render)"
                        is CallResult.Err -> "✗ ${r.message}"
                    }
                }
            }) { Text("Live token") }
        }

        Text(status, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Event ${e.id}  ·  level ${e.alarmLevel}", style = MaterialTheme.typography.titleSmall)
                        e.alarmTime?.let { Text(fmt.format(Date(it)), fontFamily = FontFamily.Monospace) }
                        Text("video: ${e.alarmVideoUrl ?: "(not uploaded)"}", style = MaterialTheme.typography.bodySmall)
                        if (e.alarmVideoUrl == null && e.id != null) {
                            OutlinedButton(onClick = {
                                scope.launch { deps.sentry.requestUpload(listOf(e.id!!)) ; status = "Upload requested for ${e.id}" }
                            }) { Text("Ask car to upload") }
                        }
                    }
                }
            }
        }
    }
}
