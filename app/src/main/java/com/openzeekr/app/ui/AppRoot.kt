package com.openzeekr.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.openzeekr.app.Deps

private enum class Tab(val label: String, val icon: ImageVector) {
    CONTROLS("Controls", Icons.Filled.DirectionsCar),
    PARKING("Parking", Icons.Filled.LocalParking),
    SENTRY("Sentry", Icons.Filled.Videocam),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun AppRoot(deps: Deps) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toTypedArray() }

    Scaffold(
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
        }
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tabs[tab]) {
            Tab.CONTROLS -> ControlsScreen(deps, m)
            Tab.PARKING -> ParkingScreen(deps, m)
            Tab.SENTRY -> SentryScreen(deps, m)
            Tab.SETTINGS -> SettingsScreen(deps, m)
        }
    }
}
