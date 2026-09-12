package com.openzeekr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Category
import com.openzeekr.app.remote.Command
import kotlinx.coroutines.launch

@Composable
fun ControlsScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready.") }

    fun fire(label: String, block: suspend () -> CallResult<*>) {
        status = "$label…"
        scope.launch {
            status = when (val r = block()) {
                is CallResult.Ok -> "$label ✓"
                is CallResult.Err -> "$label ✗ ${r.message}"
            }
        }
    }

    val byCategory = remember { Command.entries.groupBy { it.category } }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Digital Key (BLE)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Placeholder — DK handshake not reversed yet; these will report an error until a real DkSession is wired.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ElevatedButton(onClick = {
                            fire("DK Unlock") {
                                runCatching { deps.lock.unlock() }
                                    .fold({ CallResult.Ok(it) }, { CallResult.Err(it.message ?: "error") })
                            }
                        }) { Text("Unlock (DK)") }
                        OutlinedButton(onClick = {
                            fire("DK Lock") {
                                runCatching { deps.lock.lock() }
                                    .fold({ CallResult.Ok(it) }, { CallResult.Err(it.message ?: "error") })
                            }
                        }) { Text("Lock (DK)") }
                    }
                }
            }
        }

        item { ProximityCard(deps) }

        item {
            Text(status, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        Category.entries.forEach { cat ->
            val cmds = byCategory[cat].orEmpty()
            if (cmds.isNotEmpty()) {
                item { Text(cat.name, style = MaterialTheme.typography.titleSmall) }
                items(cmds) { cmd ->
                    Button(
                        onClick = { fire(cmd.title) { deps.control.send(cmd) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${cmd.title}   ·   ${cmd.serviceId}/${cmd.command}") }
                }
            }
        }
    }
}

/** Approach-unlock / walk-away-lock card: live RSSI, zone, and tunable thresholds. */
@Composable
private fun ProximityCard(deps: Deps) {
    val context = LocalContext.current
    val prox by deps.proximity.state.collectAsState()
    val cfg by deps.config.config.collectAsState()

    fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPerms(): Boolean = requiredPerms().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.all { it }) deps.proximity.start() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Proximity (RSSI) unlock/lock", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = prox.running,
                    onCheckedChange = { on ->
                        if (on) { if (hasPerms()) deps.proximity.start() else permLauncher.launch(requiredPerms()) }
                        else deps.proximity.stop()
                    },
                )
            }

            val zoneText = when (prox.zone) {
                ProximityController.Zone.NEAR -> "NEAR → unlock"
                ProximityController.Zone.FAR -> "FAR → lock"
                ProximityController.Zone.UNKNOWN -> "—"
            }
            Text(
                "RSSI ${prox.smoothedRssi ?: "--"} dBm (raw ${prox.rawRssi ?: "--"})   zone: $zoneText",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            if (prox.lastAction.isNotBlank())
                Text("last: ${prox.lastAction}", style = MaterialTheme.typography.bodySmall)
            prox.error?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }

            // Unlock threshold (closer = higher/less negative).
            Text("Unlock at ≥ ${cfg.unlockRssi} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = cfg.unlockRssi.toFloat(),
                onValueChange = { v -> deps.config.update { it.copy(unlockRssi = v.toInt()) } },
                valueRange = -100f..-30f,
            )
            // Lock threshold (farther = lower/more negative).
            Text("Lock at ≤ ${cfg.lockRssi} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = cfg.lockRssi.toFloat(),
                onValueChange = { v -> deps.config.update { it.copy(lockRssi = v.toInt()) } },
                valueRange = -100f..-30f,
            )

            OutlinedTextField(
                value = cfg.proximityDeviceMac,
                onValueChange = { v -> deps.config.update { it.copy(proximityDeviceMac = v) } },
                label = { Text("Vehicle BLE MAC (blank = strongest advertiser)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ranging is real (live BLE scan). The unlock/lock action is the DK placeholder until the DK session is reversed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
