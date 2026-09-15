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
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import com.openzeekr.app.net.model.ClimateStatusVo
import com.openzeekr.app.net.model.ElectricStatusVo
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
    val climate = status?.additionalVehicleStatus?.climateStatus

    // Home-screen climate glyph: while A/C runs, show whether it's cooling or heating the cabin —
    // compare interior temp to the target setpoint. Cooling → blue snowflake; heating → orange sun.
    val acOn = climate?.acOn == true
    val acHeating = acOn && run {
        val set = climate?.crSetTemp?.toDoubleOrNull()
        val inside = climate?.interiorTemp?.toDoubleOrNull()
        set != null && inside != null && inside < set
    }
    val climateIcon = if (acHeating) Icons.Filled.WbSunny else Icons.Filled.AcUnit
    val climateTint = if (acHeating) Brand.energy else Brand.accent

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
        // Local paint preview (visual only — does not change the real car colour). Lets you see the
        // two-layer recolour across the palette; resets when the model changes.
        var previewColor by remember(model) { mutableStateOf<PaintColor?>(null) }
        val shownPaint = previewColor ?: paint
        Hero(model, shownPaint, charging, soc, powerKw)
        if (model.bodyAsset != null && model.colors.size > 1)
            ColorSwatches(model.colors, shownPaint) { previewColor = it }

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
                Ctl(climateIcon, "Climate", tint = climateTint, active = acOn, modifier = Modifier.weight(1f)) { showClimate = true }
                // Always open the sheet — charge limit, battery pre-conditioning (a PRE-charge
                // action) and the charge-port control all live there, so it must be reachable when
                // unplugged too, not only mid-charge.
                ChargeCtl(charging, plugged, soc, powerKw, Modifier.weight(1f)) { showCharge = true }
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

    if (showCharge) ChargeSheet(status, soc, powerKw, charging, plugged, elec,
        onCmd = { c, extra -> fire("Charge") { deps.control.send(c, extra) } }, onDismiss = { showCharge = false })
    if (showClimate) ClimateSheet(status?.additionalVehicleStatus?.climateStatus,
        onCmd = { c, extra -> fire("Climate") { deps.control.send(c, extra) } }, onDismiss = { showClimate = false })
}

@Composable
private fun Hero(model: CarModel, paint: PaintColor, charging: Boolean, soc: Float?, powerKw: Double?) {
    val trans = rememberInfiniteTransition(label = "charge")
    val breathe by trans.animateFloat(0.04f, 0.24f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    // Intelligent backdrop: a soft studio "spotlight" whose brightness CONTRASTS the paint, so a
    // dark car (Onyx/Titanium) sits on a light card and a bright car (Crystal White/Silver) sits on
    // a dimmer one — no more black-on-black. Driven by the paint's luminance.
    // 7GT test: flat white studio card. Others use the luminance-contrast backdrop.
    val (spot, edge) = heroBackdrop(paint.color)
    val heroBg: Brush = if (model.key == "7GT") androidx.compose.ui.graphics.SolidColor(Color.White)
        else Brush.radialGradient(listOf(spot, edge))
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .height(196.dp).clip(RoundedCornerShape(22.dp))
            .background(heroBg),
    ) {
        if (charging) Box(Modifier.matchParentSize().background(Brand.energy.copy(alpha = breathe)))
        // Two-layer paint (isolated mask): recolour the grayscale metallic body with a
        // LUMINANCE-PRESERVING matrix (out_ch = target_ch · (0.2126R+0.7152G+0.0722B)) — strips any
        // base tint while keeping reflections/curvature shading — then draw the details/trim/wheels
        // untouched on top. Both layers share the exact same box so they register pixel-for-pixel.
        // Falls back to the flat white render for models without layer art.
        val body = model.bodyAsset?.let { rememberAssetBitmap(it) }
        val details = model.detailsAsset?.let { rememberAssetBitmap(it) }
        val carMod = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 4.dp).aspectRatio(16f / 8f)
        if (body != null && details != null) {
            Image(bitmap = body, contentDescription = model.displayName, modifier = carMod,
                colorFilter = ColorFilter.colorMatrix(luminanceRecolor(paint.color)))
            Image(bitmap = details, contentDescription = null, modifier = carMod)
        } else {
            val bmp = rememberAssetBitmap(model.renderAsset)
            if (bmp != null) Image(bitmap = bmp, contentDescription = model.displayName, modifier = carMod)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(paint.color))
            Text(paint.name, color = Brand.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * Luminance-preserving recolour matrix for an isolated paint mask: each output channel =
 * target channel × Rec.709 luma (0.2126R + 0.7152G + 0.0722B), alpha untouched. Generated from the
 * paint hex, so it reproduces the per-finish calibrated matrices while also covering colours (e.g.
 * Mystic Lilac) that aren't in the six-swatch reference set.
 */
private fun luminanceRecolor(c: Color): ColorMatrix {
    val r = c.red; val g = c.green; val b = c.blue
    return ColorMatrix(
        floatArrayOf(
            0.2126f * r, 0.7152f * r, 0.0722f * r, 0f, 0f,
            0.2126f * g, 0.7152f * g, 0.0722f * g, 0f, 0f,
            0.2126f * b, 0.7152f * b, 0.0722f * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

/** Studio backdrop (spot, edge) chosen to CONTRAST the paint luminance, so every finish stays
 *  legible — a dark car gets a bright card, a bright car gets a dim one. */
private fun heroBackdrop(paint: Color): Pair<Color, Color> {
    val lum = 0.2126f * paint.red + 0.7152f * paint.green + 0.0722f * paint.blue
    val spot = when {
        lum < 0.30f -> Color(0xFFECEDF0)   // dark car → bright card
        lum > 0.72f -> Color(0xFFAAADB4)   // bright car → dim card
        else -> Color(0xFFCED1D6)
    }
    val edge = Color(red = spot.red * 0.80f, green = spot.green * 0.80f, blue = spot.blue * 0.80f, alpha = 1f)
    return spot to edge
}

@Composable
private fun ColorSwatches(colors: List<PaintColor>, selected: PaintColor, onPick: (PaintColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { pc ->
            val sel = pc.name == selected.name
            Box(
                Modifier.size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(pc.color)
                    .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else Brand.line, CircleShape)
                    .clickable { onPick(pc) },
            )
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
    charging: Boolean, plugged: Boolean, elec: ElectricStatusVo?,
    onCmd: (Command, List<ServiceParameter>) -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val portOpen = elec?.chargePortOpen == true
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
                Text("Charge limit (target)", fontWeight = FontWeight.SemiBold); Text("${limit.toInt()}%", color = Brand.muted, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = limit, onValueChange = { limit = it }, valueRange = 50f..100f, steps = 9,
                // soc is TENTHS of a percent (stock: 90.3% = "903", 94.9% = "949"). Send pct×10.
                onValueChangeFinished = { onCmd(Command.SET_CHARGE_SOC, listOf(ServiceParameter("soc", (limit.toInt() * 10).toString()))) })
            SheetToggleRow("Battery temp regulation", "Precondition the pack — run before charging",
                checked = elec?.hvBatteryPreHeatingActive == true) { on ->
                onCmd(if (on) Command.BATTERY_PREHEAT_ON else Command.BATTERY_PREHEAT_OFF, emptyList())
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Charge port", fontWeight = FontWeight.SemiBold)
                Text(if (portOpen) "Open" else "Closed", color = if (portOpen) Brand.good else Brand.muted, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Open port", Modifier.weight(1f), enabled = !portOpen, tint = Brand.good) { onCmd(Command.CHARGE_LID_OPEN, emptyList()) }
                GhostButton("Close port", Modifier.weight(1f), enabled = portOpen) { onCmd(Command.CHARGE_LID_CLOSE, emptyList()) }
            }
            if (charging) GhostButton("Stop charging", Modifier.fillMaxWidth().padding(top = 12.dp), tint = Brand.crit) { onCmd(Command.CHARGING_OFF, emptyList()) }
            else PrimaryButton("Start charging", Modifier.fillMaxWidth().padding(top = 12.dp)) { onCmd(Command.CHARGING_ON, emptyList()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateSheet(climate: ClimateStatusVo?, onCmd: (Command, List<ServiceParameter>) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    // Target A/C temperature (°C): the car doesn't report the setpoint (only interiorTemp), so seed
    // to 22.0 and keep the user's choice for the session.
    var temp by remember { mutableStateOf(22.0) }
    // A/C ON + temperature as ONE explicit ZAF command (no reliance on AC_ON's base params + dedup).
    // WIRE value MUST be dot-decimal ("21.6"): the car rejects a locale comma ("21,6") — so format
    // AC.temp with Locale.US (fmt1 stays locale-aware, but only for on-screen display).
    fun acOnParams() = listOf(
        ServiceParameter("AC", "true"),
        ServiceParameter("AC.temp", String.format(java.util.Locale.US, "%.1f", temp)),
        ServiceParameter("AC.duration", "15"),
    )
    val cabin = climate?.interiorTemp?.takeIf { it.isNotBlank() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Climate", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (cabin != null) Text("Cabin $cabin °C", color = Brand.muted, fontSize = 12.5.sp)
            }
            SheetToggleRow("Air conditioning", "Precondition the cabin", checked = climate?.acOn == true) { on ->
                if (on) onCmd(Command.CLIMATE_ZAF, acOnParams())
                else onCmd(Command.CLIMATE_ZAF, listOf(ServiceParameter("AC", "false")))
            }
            // Target temperature stepper — fires A/C on with the new setpoint so it applies live.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Target temperature", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepBtn("−") { if (temp > 15.5) { temp -= 0.5; onCmd(Command.CLIMATE_ZAF, acOnParams()) } }
                    Text("${fmt1(temp)} °C", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    StepBtn("+") { if (temp < 28.5) { temp += 0.5; onCmd(Command.CLIMATE_ZAF, acOnParams()) } }
                }
            }
            SheetToggleRow("Defrost", "Windscreen", checked = climate?.defrostOn == true) { on -> onCmd(if (on) Command.DEFROST_ON else Command.DEFROST_OFF, emptyList()) }
            SeatClimateGrid(climate, onCmd)
            // A/C vent + steering: state fields are ambiguous in the all-off capture (report "2"), so
            // these stay session-local until an on-state capture pins their on/off values.
            SheetToggleRow("A/C vent", "Fresh-air ventilation (no cooling)") { on -> onCmd(if (on) Command.CABIN_ON else Command.CABIN_OFF, emptyList()) }
            SheetToggleRow("Steering wheel heat", "") { on -> onCmd(if (on) Command.STEER_WHEEL_ON else Command.STEER_WHEEL_OFF, emptyList()) }
        }
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
}

/**
 * Per-seat heating grid (captured 2026-09-16). Each seat = serviceId ZAF `SH.<pos>` where
 * pos 11=driver, 19=passenger, 21=rear-left, 29=rear-right; level 1–3 (+`.duration`), off=false.
 * Live level reads back from climateStatus `*HeatSts` (0–3). Tap a seat to cycle 0→1→2→3→0.
 */
@Composable
private fun SeatClimateGrid(climate: ClimateStatusVo?, onCmd: (Command, List<ServiceParameter>) -> Unit) {
    // Unified per-seat control: heat AND cool on the same seat, laid out like the cabin (front row,
    // then rear). One combined level per seat: + = heat 1..3, − = cool 1..3, 0 = off (read back from
    // *HeatSts / *VentDetail). Tapping cycles 0→H1→H2→H3→C1→C2→C3→0; each step sends ONE ZAF command
    // that sets heat + cool together (they're mutually exclusive on a seat).
    fun combined(heat: String?, cool: String?): Int {
        val h = heat?.toIntOrNull() ?: 0; val c = cool?.toIntOrNull() ?: 0
        return if (h > 0) h else if (c > 0) -c else 0
    }
    val seats = listOf(
        SeatSpec("Driver", "11", combined(climate?.drvHeatSts, climate?.drvVentDetail)),
        SeatSpec("Passenger", "19", combined(climate?.passHeatingSts, climate?.passVentDetail)),
        SeatSpec("Rear L", "21", combined(climate?.rlHeatingSts, climate?.rlVentDetail)),
        SeatSpec("Rear R", "29", combined(climate?.rrHeatingSts, climate?.rrVentDetail)),
    )
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Seat climate", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("tap: heat → cool → off", color = Brand.faint, fontSize = 11.sp)
        }
        seats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    SeatClimateTile(s.label, s.level, Modifier.weight(1f)) { next ->
                        val pos = s.pos
                        val params = when {
                            next == 0 -> listOf(ServiceParameter("SH.$pos", "false"), ServiceParameter("SV.$pos", "false"))
                            next > 0 -> listOf(
                                ServiceParameter("SH.$pos", "true"), ServiceParameter("SH.$pos.level", next.toString()),
                                ServiceParameter("SH.$pos.duration", "15"), ServiceParameter("SV.$pos", "false"))
                            else -> listOf(
                                ServiceParameter("SV.$pos", "true"), ServiceParameter("SV.$pos.level", (-next).toString()),
                                ServiceParameter("SV.$pos.duration", "15"), ServiceParameter("SH.$pos", "false"))
                        }
                        onCmd(Command.CLIMATE_ZAF, params)
                    }
                }
            }
        }
    }
}

private data class SeatSpec(val label: String, val pos: String, val level: Int)

@Composable
private fun SeatClimateTile(label: String, carLevel: Int, modifier: Modifier = Modifier, onSet: (Int) -> Unit) {
    var level by remember(carLevel) { mutableStateOf(carLevel) }
    val heat = level > 0; val cool = level < 0
    val color = if (heat) Brand.energy else if (cool) Brand.accent else Brand.muted
    val mag = if (level < 0) -level else level
    // 0 → 1 → 2 → 3 → −1 → −2 → −3 → 0
    fun nextLevel(cur: Int) = when {
        cur in 0..2 -> cur + 1
        cur == 3 -> -1
        cur in -2..-1 -> cur - 1
        else -> 0
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (level != 0) color.copy(alpha = 0.16f) else Brand.surface2)
            .clickable { val n = nextLevel(level); level = n; onSet(n) }
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.EventSeat, label, tint = if (level != 0) color else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            color = if (level != 0) color else MaterialTheme.colorScheme.onSurface)
        Text(if (heat) "Heat $mag" else if (cool) "Cool $mag" else "Off", fontSize = 11.sp,
            color = if (level != 0) color else Brand.muted, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i ->
                Box(Modifier.size(width = 11.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < mag) color else Brand.surface3))
            }
        }
    }
}

@Composable
private fun SheetToggleRow(title: String, subtitle: String, checked: Boolean = false, onToggle: (Boolean) -> Unit) {
    // Controlled: seed from the car's real state and re-seed whenever a fresh status flips `checked`,
    // so the toggle reflects the vehicle instead of always starting off. Green = active.
    var on by remember(checked) { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        Switch(checked = on, onCheckedChange = { on = it; onToggle(it) }, colors = brandSwitchColors(Brand.good))
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
