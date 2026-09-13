package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    VEHICLE("Vehicle", Icons.Filled.DirectionsCar),
    PARKING("Parking", Icons.Filled.LocalParking),
    SECURITY("Security", Icons.Filled.Shield),
    KEY("Key", Icons.Filled.VpnKey),
    SETTINGS("Settings", Icons.Filled.Settings),
    // Sentry footage/live-view stays hidden (sentinel-monitoring-service is CN-only /
    // unrouted on EU). SentryScreen is kept in the tree for when a workaround is found.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(deps: Deps) {
    val tabs = remember { Tab.entries.toTypedArray() }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbar: (String) -> Unit = { msg -> scope.launch { snackbarHost.showSnackbar(msg) } }

    // Flow (mirrors the stock app): logged out -> Settings/login; logged in but no
    // key yet -> Key provisioning; once provisioned -> Controls, and the foreground
    // key service runs to keep BLE connected.
    val cfg by deps.config.config.collectAsState()
    val prov by deps.provisioning.state.collectAsState()
    val loggedIn = cfg.accessToken.isNotBlank()
    val provisioned = remember(prov.step) { deps.dkIdentity.isProvisioned } ||
        prov.step == DkProvisioning.Step.DONE

    // First run: guided wizard (login → key). Skip straight to the app once done.
    if (!cfg.onboardingDone) {
        OnboardingScreen(deps, onDone = { deps.config.update { it.copy(onboardingDone = true) } })
        return
    }

    AppBootstrap(deps, serviceEnabled = loggedIn && provisioned)

    val bleState by deps.ble.state.collectAsState()
    val bleReady = bleState == DkBleManager.State.SESSION_READY || bleState == DkBleManager.State.CONNECTED
    val carName = cfg.carNickname.ifBlank { "My Zeekr" }
    var renaming by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }

    var tab by remember { mutableIntStateOf(Tab.SETTINGS.ordinal) }
    LaunchedEffect(loggedIn, provisioned) {
        tab = when {
            !loggedIn -> Tab.SETTINGS.ordinal
            !provisioned -> Tab.KEY.ordinal
            else -> Tab.VEHICLE.ordinal
        }
    }

    Scaffold(
        topBar = {
            val connText = when { bleReady && loggedIn -> "BLE · Cloud"; bleReady -> "BLE"; loggedIn -> "Cloud"; else -> "Offline" }
            val connColor = when { bleReady -> Brand.good; loggedIn -> Brand.accent; else -> Brand.faint }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandBadge(size = 36)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(carName, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.Edit, "Rename car", tint = Brand.faint,
                            modifier = Modifier.size(15.dp).clickable { draftName = carName; renaming = true })
                    }
                    Text(if (cfg.vin.isNotBlank()) "VIN ••••${cfg.vin.takeLast(3)}" else "Tap the pencil to name your car",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(connColor))
                    Text(connText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tabs[tab]) {
            Tab.VEHICLE -> VehicleScreen(deps, snackbar, m)
            Tab.PARKING -> ParkingScreen(deps, m)
            Tab.SECURITY -> SecurityScreen(deps, snackbar, m)
            Tab.KEY -> androidx.compose.foundation.layout.Box(m) {
                SetupScreen(
                    provisioning = deps.provisioning,
                    ble = deps.ble,
                    lock = deps.lock,
                    config = deps.config,
                    isProvisioned = { deps.dkIdentity.isProvisioned },
                    snackbar = snackbar,
                )
            }
            Tab.SETTINGS -> SettingsScreen(deps, m)
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name your car") },
            text = {
                OutlinedTextField(
                    value = draftName, onValueChange = { draftName = it },
                    singleLine = true, label = { Text("Car name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = draftName.trim()
                    deps.config.update { it.copy(carNickname = n) }
                    if (n.isNotBlank()) deps.appScope.launch { runCatching { deps.control.renameVehicle(n) } }
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}
