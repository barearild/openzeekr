package com.openzeekr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.ProximityService
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Category
import com.openzeekr.app.remote.Command
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    fun fire(label: String, block: suspend () -> CallResult<*>) {
        snackbar("$label…")
        scope.launch {
            when (val r = block()) {
                is CallResult.Ok -> snackbar("$label ✓")
                is CallResult.Err -> snackbar("$label ✗  ${r.message}")
            }
        }
    }

    fun fireDk(label: String, block: suspend () -> Boolean) =
        fire(label) {
            runCatching { block() }.fold({ CallResult.Ok(it) }, { CallResult.Err(it.message ?: "error") })
        }

    val byCategory = remember(Unit) { Command.entries.groupBy { it.category } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionHeader("Quick actions") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAction("Unlock", Icons.Filled.LockOpen, Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.primary) { fireDk("DK Unlock") { deps.lock.unlock() } }
                QuickAction("Lock", Icons.Filled.Lock, Modifier.weight(1f)) { fireDk("DK Lock") { deps.lock.lock() } }
                QuickAction("Climate", Icons.Filled.Thermostat, Modifier.weight(1f)) { fire("Climate On") { deps.control.send(Command.AC_ON) } }
                QuickAction("Locate", Icons.Filled.Campaign, Modifier.weight(1f)) { fire("Flash + Horn") { deps.control.send(Command.FLASH_HORN) } }
            }
        }

        item { ProximityCard(deps) }

        byCategory.forEach { (cat, cmds) ->
            item { SectionHeader(cat.name.lowercase().replaceFirstChar { it.uppercase() }) }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cmds.forEach { cmd ->
                        CommandTile(cmd.title, iconFor(cmd), onClick = { fire(cmd.title) { deps.control.send(cmd) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit,
) {
    val onAccent = if (accent == MaterialTheme.colorScheme.primary)
        MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (accent == MaterialTheme.colorScheme.primary) accent else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = onAccent, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = onAccent)
        }
    }
}

/** Approach-unlock / walk-away-lock: live RSSI, zone, tunable thresholds. */
@Composable
private fun ProximityCard(deps: Deps) {
    val context = LocalContext.current
    val prox by deps.proximity.state.collectAsState()
    val cfg by deps.config.config.collectAsState()

    // BLE perms gate the scan; POST_NOTIFICATIONS is also requested for the
    // foreground-service notice (not required for the scan to run).
    fun blePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun requestPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            blePerms() + Manifest.permission.POST_NOTIFICATIONS
        else blePerms()

    fun hasBlePerms(): Boolean = blePerms().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> if (hasBlePerms()) ProximityService.start(context) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    Column {
                        Text("Proximity unlock", fontWeight = FontWeight.SemiBold)
                        Text("RSSI approach / walk-away", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = prox.running,
                    onCheckedChange = { on ->
                        if (on) {
                            if (hasBlePerms()) ProximityService.start(context) else permLauncher.launch(requestPerms())
                        } else ProximityService.stop(context)
                    },
                )
            }

            AnimatedVisibility(visible = prox.running) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val zone = when (prox.zone) {
                        ProximityController.Zone.NEAR -> "NEAR → unlock"
                        ProximityController.Zone.FAR -> "FAR → lock"
                        ProximityController.Zone.UNKNOWN -> "—"
                    }
                    RssiMeter(prox.smoothedRssi, cfg.lockRssi, cfg.unlockRssi)
                    Text("${prox.smoothedRssi ?: "--"} dBm   ·   $zone",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (prox.lastAction.isNotBlank())
                        Text("last: ${prox.lastAction}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    prox.error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }

            Text("Unlock at ≥ ${cfg.unlockRssi} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = cfg.unlockRssi.toFloat(),
                onValueChange = { v -> deps.config.update { it.copy(unlockRssi = v.toInt()) } },
                valueRange = -100f..-30f,
            )
            Text("Lock at ≤ ${cfg.lockRssi} dBm", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = cfg.lockRssi.toFloat(),
                onValueChange = { v -> deps.config.update { it.copy(lockRssi = v.toInt()) } },
                valueRange = -100f..-30f,
            )
            OutlinedTextField(
                value = cfg.proximityDeviceMac,
                onValueChange = { v -> deps.config.update { it.copy(proximityDeviceMac = v) } },
                label = { Text("Vehicle BLE MAC (blank = strongest)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            BatteryOptimizationRow(context)
        }
    }
}

/** Shows whether the app is exempt from battery optimization and lets the user fix it. */
@Composable
private fun BatteryOptimizationRow(context: android.content.Context) {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    // Recomputed on each recomposition (e.g. after returning from Settings).
    val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (exempt) "Background: unrestricted ✓" else "Background: restricted — may be killed",
            style = MaterialTheme.typography.bodySmall,
            color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!exempt) {
            androidx.compose.material3.TextButton(onClick = {
                @Suppress("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }) { Text("Allow") }
        }
    }
}

/** A slim bar showing where the current RSSI sits on the −100…−30 dBm range. */
@Composable
private fun RssiMeter(rssi: Int?, lock: Int, unlock: Int) {
    val frac = if (rssi == null) 0f else ((rssi + 100f) / 70f).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Box(
            Modifier.fillMaxWidth(frac).height(8.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
