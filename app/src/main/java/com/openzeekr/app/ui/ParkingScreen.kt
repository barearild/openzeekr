package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ble.rpa.RpaReq
import com.openzeekr.app.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Where's my car — MapLibre location + navigate deeplink.
        CarLocationSection(deps)

        // Remote parking — BLOCKED. RPA is gated on the car side: it needs a DK 3.0 (CCC Wallet SE)
        // key with UWB/secure ranging, which our clean-room BLE key can't satisfy — the car refuses
        // the REQ_MODE→SYNC handshake (0x100a). Not usable until we find a DK3.0 workaround or Zeekr
        // ships an official remote-park path. Shown disabled so it's clear it exists but is gated.
        CockpitCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.faint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocalParking, null, tint = Brand.faint, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Remote Parking", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Brand.muted)
                    Text("Gated by the car — needs a DK 3.0 key (UWB). Not usable yet; awaiting a workaround or official support.",
                        color = Brand.faint, fontSize = 12.sp)
                }
                Row(
                    Modifier.clip(CircleShape).background(Brand.faint.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { Text("Locked", color = Brand.faint, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteParkingControls(rpa: RpaController, state: RpaController.UiState) {
    val phase = state.phase
    val connecting = phase == RpaController.Phase.CONNECTING
    val canConnect = phase == RpaController.Phase.IDLE || phase == RpaController.Phase.ERROR || phase == RpaController.Phase.DONE
    val active = phase != RpaController.Phase.IDLE && phase != RpaController.Phase.ERROR && phase != RpaController.Phase.DONE

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalParking, null, tint = Brand.accent, modifier = Modifier.size(21.dp))
            }
            Text("Remote Parking", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            PhasePill(phase, connecting)
        }
        state.message?.let {
            Text(it, color = if (phase == RpaController.Phase.ERROR) Brand.crit else Brand.muted, fontSize = 12.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (connecting) "Connecting…" else "Connect", Modifier.weight(1f), enabled = canConnect) { rpa.begin() }
            GhostButton("Stop", Modifier.weight(1f), enabled = active, tint = Brand.crit) { rpa.stop() }
        }

        // DEBUG: fire every maneuver opcode once (skips the REQ_MODE→SYNC gate) to learn if
        // any command downstream gets a non-0x100a response. RPA is absent from stock Android
        // (iOS/DK3.0/UWB-gated), so this is exploratory. ⚠️ Keep clear space around the car.
        GhostButton("Blind probe (debug) — watch logcat", Modifier.fillMaxWidth(), tint = Brand.energy) { rpa.blindProbe() }
        Text("Fires all park/move opcodes once, ignoring the car's gate. Only run with clear space around the car — if the car accepts an autonomous command it can move.",
            color = Brand.faint, fontSize = 11.sp)

        SectionHeader("Park in")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Search", Modifier.weight(1f)) { rpa.searchSlot() }
            Tile("Park in", Modifier.weight(1f)) { rpa.startParkIn() }
            Tile("Continue", Modifier.weight(1f)) { rpa.continueParking() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Pause", Modifier.weight(1f)) { rpa.pause() }
            Tile("Undo", Modifier.weight(1f)) { rpa.undo() }
            Spacer(Modifier.weight(1f))
        }

        SectionHeader("Park out")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Parallel R" to RpaReq.PARALLEL_RIGHT_OUT,
                "Parallel L" to RpaReq.PARALLEL_LEFT_OUT,
                "Head-Perp R" to RpaReq.HEAD_PERPENDICULAR_RIGHT_OUT,
                "Head-Perp L" to RpaReq.HEAD_PERPENDICULAR_LEFT_OUT,
                "Tail-Perp R" to RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT,
                "Tail-Perp L" to RpaReq.TAIL_PERPENDICULAR_LEFT_OUT,
            ).forEach { (label, dir) -> Chip(label) { rpa.startParkOut(dir) } }
        }

        SectionHeader("Hold to move")
        Text("Press and hold; release stops instantly (the car halts the moment the 500 ms heartbeat drops).",
            color = Brand.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldPad("Forward", Icons.Filled.ArrowUpward, Modifier.weight(1f), onHold = { rpa.holdMove(true) }, onRelease = { rpa.releaseMove() })
            HoldPad("Backward", Icons.Filled.ArrowDownward, Modifier.weight(1f), onHold = { rpa.holdMove(false) }, onRelease = { rpa.releaseMove() })
        }
    }
}

@Composable
private fun PhasePill(phase: RpaController.Phase, connecting: Boolean) {
    val (label, color) = when (phase) {
        RpaController.Phase.READY -> "Ready" to Brand.good
        RpaController.Phase.MOVING, RpaController.Phase.PARKING_IN, RpaController.Phase.PARKING_OUT -> "Active" to Brand.accent
        RpaController.Phase.PAUSED -> "Paused" to Brand.energy
        RpaController.Phase.DONE -> "Done" to Brand.good
        RpaController.Phase.ERROR -> "Error" to Brand.crit
        RpaController.Phase.CONNECTING -> "Connecting" to Brand.accent
        else -> "Idle" to Brand.faint
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (connecting) CircularProgressIndicator(Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brand.surface2).clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(Brand.surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Brand.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HoldPad(label: String, icon: ImageVector, modifier: Modifier, onHold: () -> Unit, onRelease: () -> Unit) {
    Column(
        modifier.height(100.dp).clip(RoundedCornerShape(16.dp)).background(Brand.accent.copy(alpha = 0.14f))
            .pointerInput(Unit) { detectTapGestures(onPress = { onHold(); tryAwaitRelease(); onRelease() }) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.accent)
        }
        Text(label, color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}
