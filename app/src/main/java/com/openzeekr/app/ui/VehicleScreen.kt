package com.openzeekr.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.graphics.BitmapFactory
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.config.Units
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.net.model.VehicleInfo
import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Home ("Vehicle") screen. Live status via [Deps.vehicleState] (foreground poll);
 * model/colour/render auto-detected from the vehicle-list. Charging & climate open
 * bottom-sheets wired to the reversed command catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val bleState by deps.ble.state.collectAsState()
    val bleReady = bleState == DkBleManager.State.SESSION_READY

    val status by deps.vehicleState.state.collectAsState()
    val cfg by deps.config.config.collectAsState()
    val caps by deps.capabilities.state.collectAsState()
    var info by remember { mutableStateOf<VehicleInfo?>(null) }
    LaunchedEffect(Unit) {
        deps.capabilities.ensureLoaded()
        deps.vehicleState.refresh()
        when (val r = deps.control.vehicleInfo()) {
            is CallResult.Ok -> r.value?.let { vi ->
                info = vi
                val nn = vi.nickName
                if (nn != null && nn.isNotBlank() && deps.config.current().carNickname.isBlank())
                    deps.config.update { it.copy(carNickname = nn) }
            }
            is CallResult.Err -> {}
        }
    }
    val model = remember(info) { CarCatalog.forModel(info?.model) }
    val paint = remember(info, model) { CarCatalog.colorFor(model, info?.colorName) }

    val elec = status?.additionalVehicleStatus?.electricVehicleStatus
    val safety = status?.additionalVehicleStatus?.drivingSafetyStatus
    val maint = status?.additionalVehicleStatus?.maintenanceStatus

    val locked = safety?.centralLockingStatus?.let { it == "1" } ?: true
    val charging = elec?.chargingActive == true
    val plugged = elec?.pluggedIn == true
    val socStr = elec?.stateOfCharge?.takeIf { it.isNotBlank() } ?: elec?.chargeLevel
    val soc = socStr?.toFloatOrNull()
    val rangeStr = elec?.distanceToEmptyOnBatteryOnly?.takeIf { it.isNotBlank() && it != "0" }
        ?: status?.basicVehicleStatus?.distanceToEmpty
    val powerKw = elec?.chargePowerW?.let { it / 1000.0 }

    var showCharge by remember { mutableStateOf(false) }
    var showClimate by remember { mutableStateOf(false) }

    fun fire(label: String, block: suspend () -> CallResult<*>) {
        snackbar("$label…")
        scope.launch {
            when (val r = block()) { is CallResult.Ok -> snackbar("$label ✓"); is CallResult.Err -> snackbar("$label ✗ ${r.message}") }
        }
    }
    fun door(lockIt: Boolean) {
        val n = if (lockIt) "Lock" else "Unlock"
        if (bleReady) {
            snackbar("$n (key)…")
            scope.launch {
                val ok = runCatching { if (lockIt) deps.lock.lock() else deps.lock.unlock() }.getOrDefault(false)
                snackbar(if (ok) "$n ✓" else "$n ✗")
            }
        } else fire("$n (cloud)") { deps.control.send(if (lockIt) Command.LOCK else Command.UNLOCK) }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Hero(model, paint, charging, soc, powerKw)

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem("Central lock", if (locked) "Locked" else "Unlocked", if (locked) Brand.good else Brand.energy, Modifier.weight(1f))
            StatItem("Battery", soc?.let { "${fmt(it)}%" } ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatItem("Range", rangeStr?.let { s -> s.toDoubleOrNull()?.let { Units.distance(it, cfg.distanceUnit) } ?: "$s km" } ?: "—", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        Divider()

        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ctl(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (locked) "Locked" else "Unlocked",
                    tint = if (locked) Brand.good else Brand.energy, active = true, modifier = Modifier.weight(1f)) { door(!locked) }
                Ctl(Icons.Filled.AcUnit, "Climate", modifier = Modifier.weight(1f)) { showClimate = true }
                ChargeCtl(charging, plugged, soc, powerKw, Modifier.weight(1f)) {
                    if (charging || plugged) showCharge = true
                    else fire("Charge port") { deps.control.send(Command.CHARGE_LID_OPEN) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ctl(Icons.Filled.Campaign, "Flash + Honk", modifier = Modifier.weight(1f)) { fire("Flash + Honk") { deps.control.send(Command.FLASH_HORN) } }
                // Frunk/tailgate shown only if the car reports the capability (per-VIN).
                if (caps.frunk) Ctl(Icons.Filled.Inventory2, "Frunk", modifier = Modifier.weight(1f)) { fire("Frunk") { deps.control.send(Command.FRONT_TRUNK) } }
                if (caps.tailgate) Ctl(Icons.Filled.Inventory2, "Trunk", modifier = Modifier.weight(1f)) { fire("Trunk") { deps.control.send(Command.TRUNK_OPEN) } }
                // keep the 3-across row balanced when a control is hidden
                repeat((if (caps.frunk) 0 else 1) + (if (caps.tailgate) 0 else 1)) { Spacer(Modifier.weight(1f)) }
            }
        }

        // tyres — real values: MaintenanceStatusVo.tyreStatus* is pressure in kPa.
        SectionLabel("Tyre pressure · ${Units.pressureSuffix(cfg.pressureUnit)}")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tyre("Front L", maint?.tyreStatusDriver, cfg.pressureUnit, Modifier.weight(1f)); Tyre("Front R", maint?.tyreStatusPassenger, cfg.pressureUnit, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tyre("Rear L", maint?.tyreStatusDriverRear, cfg.pressureUnit, Modifier.weight(1f)); Tyre("Rear R", maint?.tyreStatusPassengerRear, cfg.pressureUnit, Modifier.weight(1f))
        }
    }

    if (showCharge) ChargeSheet(status, soc, powerKw, charging, plugged,
        onCmd = { c, extra -> fire("Charge") { deps.control.send(c, extra) } }, onDismiss = { showCharge = false })
    if (showClimate) ClimateSheet(onCmd = { c -> fire("Climate") { deps.control.send(c) } }, onDismiss = { showClimate = false })
}

@Composable
private fun Hero(model: CarModel, paint: PaintColor, charging: Boolean, soc: Float?, powerKw: Double?) {
    val trans = rememberInfiniteTransition(label = "charge")
    val breathe by trans.animateFloat(0.04f, 0.24f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .height(196.dp).clip(RoundedCornerShape(22.dp)).background(Brand.paintCard(paint.color)),
    ) {
        if (charging) Box(Modifier.matchParentSize().background(Brand.energy.copy(alpha = breathe)))
        val bmp = rememberAssetBitmap(model.renderAsset)
        if (bmp != null) Image(bitmap = bmp, contentDescription = model.displayName,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 4.dp).aspectRatio(16f / 8f))
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(paint.color))
            Text(paint.name, color = Color.White.copy(alpha = .92f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (charging && soc != null) {
            val phase by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "soc")
            val shimmer = Brush.linearGradient(
                0f to Brand.energy.copy(alpha = .55f), 0.5f to Color.White.copy(alpha = .85f), 1f to Brand.energy.copy(alpha = .55f),
                start = Offset(-160f + phase * 320f, 0f), end = Offset(phase * 320f, 0f), tileMode = TileMode.Mirror)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .10f))) {
                Box(Modifier.fillMaxWidth(soc / 100f).height(4.dp).background(shimmer))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Brand.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Ctl(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface,
                active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(60.dp).clip(CircleShape)
            .background(if (active) tint.copy(alpha = .16f) else Brand.surface2)
            .border(1.dp, if (active) tint else Brand.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) tint else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
        Text(label, color = Brand.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChargeCtl(charging: Boolean, plugged: Boolean, soc: Float?, powerKw: Double?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            if (charging && soc != null) {
                Canvas(Modifier.size(60.dp)) {
                    val sw = 4.dp.toPx()
                    drawArc(Color.White.copy(alpha = .13f), -90f, 360f, false, style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(Brand.energy, -90f, 360f * (soc / 100f), false, style = Stroke(sw, cap = StrokeCap.Round))
                }
            } else {
                Box(Modifier.size(60.dp).clip(CircleShape).background(Brand.surface2).border(1.dp, Brand.line, CircleShape))
            }
            Icon(Icons.Filled.Bolt.takeIf { charging } ?: Icons.Filled.Power, "Charging",
                tint = if (charging) Brand.energy else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (charging) 18.dp else 24.dp))
        }
        Text(
            when { charging -> powerKw?.let { "${fmt1(it)} kW · 1-phase" } ?: "Charging"; plugged -> "Plugged in"; else -> "Charge port" },
            color = Brand.muted, fontSize = 11.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Tyre(pos: String, kpa: String?, unit: String, modifier: Modifier = Modifier) {
    val kpaV = kpa?.toDoubleOrNull()
    val warn = kpaV != null && kpaV < 220.0   // < ~2.2 bar
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(pos, color = Brand.muted, fontSize = 11.sp)
        Text(kpaV?.let { Units.pressure(it, unit) } ?: "—", color = if (warn) Brand.energy else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeSheet(
    status: VehicleStatusBean?, soc: Float?, powerKw: Double?,
    charging: Boolean, plugged: Boolean, onCmd: (Command, List<ServiceParameter>) -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    var limit by remember { mutableStateOf(80f) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Charging", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(soc?.let { "${fmt(it)}%" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (charging) (powerKw?.let { "${fmt1(it)} kW · 1-phase" } ?: "Charging") else if (plugged) "Plugged in" else "Unplugged",
                        color = if (charging) Brand.energy else Brand.muted, fontWeight = FontWeight.SemiBold)
                    status?.additionalVehicleStatus?.electricVehicleStatus?.distanceToEmptyOnBatteryOnly?.let { Text("$it km range", color = Brand.muted, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Charge limit", fontWeight = FontWeight.SemiBold); Text("${limit.toInt()}%", color = Brand.muted, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = limit, onValueChange = { limit = it }, valueRange = 50f..100f, steps = 9,
                onValueChangeFinished = { onCmd(Command.SET_CHARGE_SOC, listOf(ServiceParameter("soc", limit.toInt().toString()))) })
            SheetToggleRow("Battery temp regulation", "Keep the pack in its ideal window") { on ->
                onCmd(if (on) Command.BATTERY_PREHEAT_ON else Command.BATTERY_PREHEAT_OFF, emptyList())
            }
            if (charging) GhostButton("Stop charging", Modifier.fillMaxWidth().padding(top = 12.dp), tint = Brand.crit) { onCmd(Command.CHARGING_OFF, emptyList()) }
            else PrimaryButton("Start charging", Modifier.fillMaxWidth().padding(top = 12.dp)) { onCmd(Command.CHARGING_ON, emptyList()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateSheet(onCmd: (Command) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Climate", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            SheetToggleRow("Air conditioning", "Precondition the cabin") { on -> onCmd(if (on) Command.AC_ON else Command.AC_OFF) }
            SheetToggleRow("Defrost", "Windscreen") { on -> onCmd(if (on) Command.DEFROST_ON else Command.DEFROST_OFF) }
            SheetToggleRow("Seat heating", "Driver + passenger") { on -> onCmd(if (on) Command.SEAT_HEAT_ON else Command.SEAT_HEAT_OFF) }
            SheetToggleRow("Steering wheel heat", "") { on -> onCmd(if (on) Command.STEER_WHEEL_ON else Command.STEER_WHEEL_OFF) }
            Text("Rapid heat/cool, A/C vent and seat cooling use the ZAF command family — wiring pending on-car verification.",
                color = Brand.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun SheetToggleRow(title: String, subtitle: String, onToggle: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; onToggle(it) }, colors = brandSwitchColors())
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 6.dp))

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Brand.line))

/** Load a car render from assets/ (gitignored). Null if absent → hero shows just the colour card. */
@Composable
private fun rememberAssetBitmap(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(path) {
        runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()
    }
}

private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else "%.1f".format(f)
private fun fmt1(d: Double) = "%.1f".format(d)
