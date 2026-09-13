package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.config.ConfigStore
import kotlinx.coroutines.launch

/**
 * First-time digital-key setup + lock/unlock test.
 *
 * Flow: (log in first, elsewhere) -> "Set up digital key" runs the clean-room
 * provisioning (enrol cert -> bind -> key-list -> key-info -> arm BLE session),
 * then "Connect" opens the BLE link at the car and Unlock/Lock exercise it.
 */
@Composable
fun SetupScreen(
    provisioning: DkProvisioning,
    ble: DkBleManager,
    lock: DkLockController,
    config: ConfigStore,
    isProvisioned: () -> Boolean,
    snackbar: (String) -> Unit,
) {
    val prov by provisioning.state.collectAsState()
    val bleState by ble.state.collectAsState()
    val cfg by config.config.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var owner by remember { mutableStateOf(false) }

    // Provisioned = persisted credential OR provisioning just finished (reactive).
    val ready = isProvisioned() || prov.step == DkProvisioning.Step.DONE

    // Android 12+ needs runtime BLUETOOTH_SCAN/CONNECT; older needs FINE_LOCATION.
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasBlePerms() = blePerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) ble.connect(null) else snackbar("Bluetooth permission denied — enable it in system settings")
    }
    fun connect() { if (hasBlePerms()) ble.connect(null) else permLauncher.launch(blePerms) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Digital Key setup", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Text(
            "Provision this phone as its own key (own cert + key material). Needs a " +
                "completed login and the vehicle VIN first.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )

        // ---- provisioning ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Provisioning", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                StepRow("Enrol certificate", prov.step, DkProvisioning.Step.CERT)
                StepRow("Bind device to key", prov.step, DkProvisioning.Step.BIND)
                StepRow("Fetch key list", prov.step, DkProvisioning.Step.KEY_LIST)
                StepRow("Fetch key material", prov.step, DkProvisioning.Step.KEY_INFO)
                prov.message?.let { Text("• $it", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !owner, onClick = { owner = false }, label = { Text("Shared key") })
                    FilterChip(selected = owner, onClick = { owner = true }, label = { Text("Car owner") })
                }
                val busy = prov.step == DkProvisioning.Step.CERT || prov.step == DkProvisioning.Step.BIND ||
                    prov.step == DkProvisioning.Step.KEY_LIST || prov.step == DkProvisioning.Step.KEY_INFO
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            provisioning.provision(owner).onSuccess { snackbar("Digital key provisioned") }
                                .onFailure { snackbar("Provisioning failed: ${it.message}") }
                        }
                    }, enabled = !busy) {
                        Text(if (isProvisioned()) "Re-provision" else "Set up digital key")
                    }
                    if (prov.step == DkProvisioning.Step.DONE) Text("✓ ready", Modifier.padding(top = 12.dp))
                }
            }
        }

        // ---- BLE test ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · At the car", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("BLE: $bleState", fontFamily = FontFamily.Monospace,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                ble.lastError?.let { Text("! $it", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { connect() }, enabled = ready) { Text("Connect") }
                    OutlinedButton(onClick = { ble.disconnect() }) { Text("Disconnect") }
                }
                Divider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { lock.unlock() }
                                .onSuccess { snackbar(if (it) "Unlock sent" else "Unlock rejected") }
                                .onFailure { snackbar("Unlock error: ${it.message}") }
                        }
                    }, enabled = ready) { Text("Unlock") }
                    Button(onClick = {
                        scope.launch {
                            runCatching { lock.lock() }
                                .onSuccess { snackbar(if (it) "Lock sent" else "Lock rejected") }
                                .onFailure { snackbar("Lock error: ${it.message}") }
                        }
                    }, enabled = ready) { Text("Lock") }
                }
            }
        }
        // ---- passive entry ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Approach unlock & walk-away lock", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text("Unlock as you near the car, lock when you leave.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = cfg.proximityEnabled, onCheckedChange = { on ->
                        config.update { it.copy(proximityEnabled = on) }
                    })
                }
                if (cfg.proximityEnabled) {
                    Text("Sensitivity — how close before it unlocks",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("veryclose" to "Very close", "close" to "Close", "far" to "Far").forEach { (key, label) ->
                            FilterChip(selected = cfg.proximitySensitivity == key,
                                onClick = { config.update { it.copy(proximitySensitivity = key) } },
                                label = { Text(label) })
                        }
                    }
                    Text(
                        when (cfg.proximitySensitivity) {
                            "veryclose" -> "Unlocks within arm's reach · most secure"
                            "far" -> "Unlocks within ~3–4 m · most convenient"
                            else -> "Unlocks within ~1–2 m · balanced"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepRow(label: String, current: DkProvisioning.Step, self: DkProvisioning.Step) {
    val ord = DkProvisioning.Step.values()
    val done = ord.indexOf(current) > ord.indexOf(self) || current == DkProvisioning.Step.DONE
    val active = current == self
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active) CircularProgressIndicator(Modifier.height(16.dp).padding(end = 2.dp), strokeWidth = 2.dp)
        Text((if (done) "✓ " else if (active) "… " else "○ ") + label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}
