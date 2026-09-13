package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ble.rpa.RpaReq
import com.openzeekr.app.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    val rpa = deps.rpa
    val state by rpa.state.collectAsState()

    // Dead-man safety: if the screen loses foreground (background, screen off,
    // navigation away) or is disposed, immediately release — this stops the
    // 500 ms heartbeat so the car halts even if a finger-up never arrives.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) rpa.releaseMove()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            rpa.releaseMove()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Where's my car — MapLibre location + navigate deeplink
        CarLocationSection(deps)

        // Status hero
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(Brush.linearGradient(Brand.gradient))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.LocalParking, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("Remote Parking", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                }
                Text("Phase: ${state.phase}", color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium)
                state.lastStatus?.let {
                    Text("Car status byte: $it", color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Bluetooth, null, tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp))
                    Text("Rides the BLE digital-key session", color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        state.message?.let {
            Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { rpa.begin() }, modifier = Modifier.weight(1f)) { Text("Connect") }
            OutlinedButton(onClick = { rpa.stop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
            OutlinedButton(onClick = { rpa.undo() }, modifier = Modifier.weight(1f)) { Text("Undo") }
        }

        SectionHeader("Park in")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { rpa.searchSlot() }, modifier = Modifier.weight(1f)) { Text("Search") }
            FilledTonalButton(onClick = { rpa.startParkIn() }, modifier = Modifier.weight(1f)) { Text("Park In") }
            FilledTonalButton(onClick = { rpa.continueParking() }, modifier = Modifier.weight(1f)) { Text("Continue") }
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
            ).forEach { (label, dir) ->
                AssistChip(onClick = { rpa.startParkOut(dir) }, label = { Text(label) })
            }
        }

        SectionHeader("Hold to move (RSPA)")
        Text("Press and hold; release stops the 500 ms heartbeat.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldPad("Forward", Icons.Filled.ArrowUpward, Modifier.weight(1f),
                onHold = { rpa.holdMove(true) }, onRelease = { rpa.releaseMove() })
            HoldPad("Backward", Icons.Filled.ArrowDownward, Modifier.weight(1f),
                onHold = { rpa.holdMove(false) }, onRelease = { rpa.releaseMove() })
        }
    }
}

@Composable
private fun HoldPad(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onHold: () -> Unit,
    onRelease: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.height(96.dp).pointerInput(Unit) {
            detectTapGestures(onPress = { onHold(); tryAwaitRelease(); onRelease() })
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
