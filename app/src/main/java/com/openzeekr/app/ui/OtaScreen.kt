package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.OtaStatus
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Software-update (OTA) tab - UNDER DEVELOPMENT. For now it only CHECKS whether the cloud has a new
 * software version assigned to the car (the stock `ota/os/versionV2` check). Actually downloading /
 * installing an update is not implemented yet - that needs a live capture while an update is available
 * to reverse the apply flow. The check needs the overseas credentials, so on most setups it fails soft.
 */
@Composable
fun OtaScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<OtaStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Software updates")

        // Under-development notice.
        CockpitCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.energy.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Construction, null, tint = Brand.energy, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Under development", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Brand.energy)
                    Text("Not functional yet - for now it can only check whether an update is available. " +
                        "Installing updates will come once we can test against a real OTA.",
                        color = Brand.muted, fontSize = 12.sp)
                }
            }
        }

        // Check card.
        CockpitCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Vehicle software", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Check the cloud for a new software version for your car.", color = Brand.muted, fontSize = 12.sp)
                }
            }

            status?.let { s ->
                Spacer(Modifier.height(2.dp))
                Text("Current version", color = Brand.muted, fontSize = 12.sp)
                Text(s.currentVersion ?: "Unknown", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (s.updateAvailable) {
                    Text("Update available" + (s.targetVersion?.let { " - $it" } ?: ""),
                        color = Brand.good, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                } else {
                    Text("Up to date", color = Brand.muted, fontSize = 13.sp)
                }
                if (s.releaseNotes.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("Release notes", color = Brand.muted, fontSize = 12.sp)
                    s.releaseNotes.forEach { note ->
                        Text("- ${note.trim()}", color = Brand.faint, fontSize = 12.sp)
                    }
                }
            }
            error?.let { Text(it, color = Brand.crit, fontSize = 12.5.sp) }

            PrimaryButton(if (checking) "Checking…" else "Check for updates", Modifier.fillMaxWidth(), enabled = !checking) {
                checking = true; error = null
                scope.launch {
                    when (val r = deps.ota.checkUpdate()) {
                        is CallResult.Ok -> { status = r.value; error = null }
                        is CallResult.Err -> { status = null; error = "Couldn't check for updates (${r.message})." }
                    }
                    checking = false
                }
            }
            Text("Checking uses the overseas-app credentials; on most setups it isn't available yet.",
                color = Brand.faint, fontSize = 11.sp)
        }
    }
}
