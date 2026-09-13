package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkProvisioning
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    CONTROLS("Controls", Icons.Filled.DirectionsCar),
    PARKING("Parking", Icons.Filled.LocalParking),
    KEY("Key", Icons.Filled.VpnKey),
    // SENTRY hidden for now: sentinel-monitoring-service is not routed on the EU (or
    // any overseas) gateway — it's a CN-only backend, so footage + live view 404.
    // SentryScreen is kept in the tree for when a workaround is found. See README.
    SETTINGS("Settings", Icons.Filled.Settings),
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

    var tab by remember { mutableIntStateOf(Tab.SETTINGS.ordinal) }
    LaunchedEffect(loggedIn, provisioned) {
        tab = when {
            !loggedIn -> Tab.SETTINGS.ordinal
            !provisioned -> Tab.KEY.ordinal
            else -> Tab.CONTROLS.ordinal
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BrandBadge(size = 28)
                        Text("OpenZeekr", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
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
            Tab.CONTROLS -> ControlsScreen(deps, snackbar, m)
            Tab.PARKING -> ParkingScreen(deps, m)
            Tab.KEY -> androidx.compose.foundation.layout.Box(m) {
                SetupScreen(
                    provisioning = deps.provisioning,
                    ble = deps.ble,
                    lock = deps.lock,
                    isProvisioned = { deps.dkIdentity.isProvisioned },
                    snackbar = snackbar,
                )
            }
            Tab.SETTINGS -> SettingsScreen(deps, m)
        }
    }
}
