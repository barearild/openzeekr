package com.openzeekr.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.rpa.RpaReq

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    val rpa = deps.rpa
    val state by rpa.state.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Remote Parking (BLE DK)", style = MaterialTheme.typography.titleMedium)
                Text("Phase: ${state.phase}   Mode: ${state.mode}", fontFamily = FontFamily.Monospace)
                state.lastStatus?.let { Text("Car status byte: $it", fontFamily = FontFamily.Monospace) }
                state.message?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Rides the DK session. Sending is a placeholder until the DK handshake + RPA challenge grid are reversed; opcodes, heartbeat and flow are real.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { rpa.begin() }) { Text("Connect / Request RPA") }
            OutlinedButton(onClick = { rpa.stop() }) { Text("Stop") }
            OutlinedButton(onClick = { rpa.undo() }) { Text("Undo") }
        }

        Text("Park In", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { rpa.searchSlot() }) { Text("Search Slot") }
            FilledTonalButton(onClick = { rpa.startParkIn() }) { Text("Park In") }
            FilledTonalButton(onClick = { rpa.continueParking() }) { Text("Continue") }
        }

        Text("Park Out (pick direction)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dirs = listOf(
                "Parallel R" to RpaReq.PARALLEL_RIGHT_OUT,
                "Parallel L" to RpaReq.PARALLEL_LEFT_OUT,
                "Head-Perp R" to RpaReq.HEAD_PERPENDICULAR_RIGHT_OUT,
                "Head-Perp L" to RpaReq.HEAD_PERPENDICULAR_LEFT_OUT,
                "Tail-Perp R" to RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT,
                "Tail-Perp L" to RpaReq.TAIL_PERPENDICULAR_LEFT_OUT,
            )
            dirs.forEach { (label, dir) ->
                AssistChip(onClick = { rpa.startParkOut(dir) }, label = { Text(label) })
            }
        }

        Text("Hold-to-move (RSPA)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Press and hold; releasing sends BOTTOM_RELEASE and stops the 500 ms heartbeat.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldButton("▲ Forward", Modifier.height(64.dp),
                onHold = { rpa.holdMove(forward = true) }, onRelease = { rpa.releaseMove() })
            HoldButton("▼ Backward", Modifier.height(64.dp),
                onHold = { rpa.holdMove(forward = false) }, onRelease = { rpa.releaseMove() })
        }
    }
}

@Composable
private fun HoldButton(label: String, modifier: Modifier, onHold: () -> Unit, onRelease: () -> Unit) {
    Button(
        onClick = {},
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onHold()
                    val released = tryAwaitRelease()
                    onRelease()
                }
            )
        },
    ) { Text(label) }
}
