package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Security & access: sentry guard (with "auto-arm on lock"), glovebox PIN, visitor
 * mode, vehicle location, journey log. Sentry is wired to SENTINEL_ON/OFF; the rest
 * are placeholders pending their serviceId wiring (ZAD/ZAG/ms-vehicle-trail).
 */
@Composable
fun SecurityScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var sentry by remember { mutableStateOf(true) }
    var autoArm by remember { mutableStateOf(true) }
    var visitor by remember { mutableStateOf(false) }

    fun fire(label: String, block: suspend () -> CallResult<*>) {
        snackbar("$label…")
        scope.launch {
            when (val r = block()) { is CallResult.Ok -> snackbar("$label ✓"); is CallResult.Err -> snackbar("$label ✗ ${r.message}") }
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Label("Security & access")
        Column(Modifier.padding(horizontal = 20.dp)) {
            SecRow(Icons.Filled.Shield, "Sentry guard",
                if (sentry) "Armed · re-arms after each drive" else "Off") {
                Switch(checked = sentry, onCheckedChange = { on ->
                    sentry = on
                    fire(if (on) "Sentry on" else "Sentry off") { deps.control.send(if (on) Command.SENTINEL_ON else Command.SENTINEL_OFF) }
                }, colors = brandSwitchColors(Brand.good))
            }
            if (sentry) Row(
                Modifier.fillMaxWidth().padding(start = 46.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-arm every time the car locks", color = Brand.muted, fontSize = 12.5.sp)
                Switch(checked = autoArm, onCheckedChange = { autoArm = it },
                    colors = brandSwitchColors(Brand.good))
            }
            SecRow(Icons.Filled.Inventory2, "Glovebox PIN", "Lock the glovebox with a code",
                onClick = { snackbar("Glovebox PIN — coming next") }) { Chevron() }
            SecRow(Icons.Filled.Person, "Visitor mode", "Restricted access for a guest / valet") {
                Switch(checked = visitor, onCheckedChange = { on ->
                    visitor = on; snackbar(if (on) "Visitor mode on" else "Visitor mode off")
                }, colors = brandSwitchColors())
            }
            SecRow(Icons.Filled.LocationOn, "Vehicle location", "Last parked spot on the map",
                onClick = { snackbar("Opens the parked location in Maps") }) { Chevron() }
            SecRow(Icons.Filled.Timeline, "Journey log", "Trips · distance & energy · export",
                onClick = { snackbar("Journey log — coming next") }) { Chevron() }
        }
        Text(
            "Send-to-car isn't a button — OpenZeekr registers as a share target, so a pin dropped in Maps goes straight to the car's nav.",
            color = Brand.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SecRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        trailing()
    }
}

@Composable
private fun Chevron() = Text("›", color = Brand.faint, fontSize = 18.sp)

@Composable
private fun Label(text: String) =
    Text(text, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
