package com.openzeekr.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openzeekr.app.Deps
import com.openzeekr.app.config.Units
import com.openzeekr.app.net.model.JourneyTrip
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Journey log — the car's trip history (distance / energy / duration / odometer), pulled
 * from `ms-vehicle-trail/journalLog/trip/listForPage`. The whole list can be exported to a
 * CSV spreadsheet and shared out via the system share sheet (a FileProvider Uri).
 */
@Composable
fun JourneyScreen(deps: Deps, onBack: () -> Unit, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cfg by deps.config.config.collectAsState()
    var trips by remember { mutableStateOf<List<JourneyTrip>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; error = null
        when (val r = deps.journey.trips()) {
            is CallResult.Ok -> { trips = r.value; error = null }
            is CallResult.Err -> error = r.message
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    fun export() {
        if (trips.isEmpty()) { snackbar("No trips to export"); return }
        runCatching {
            val csv = buildCsv(trips, cfg.distanceUnit)
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(dir, "zeekr-journeys-$stamp.csv")
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Zeekr journey log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Export journeys"))
        }.onFailure { snackbar("Export failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header (back + title), mirroring InboxScreen.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text("Journey log", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        // Export button always visible at the top (disabled while there's nothing to export).
        PrimaryButton(
            "Export to CSV",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = trips.isNotEmpty(),
            onClick = { export() },
        )

        when {
            loading && trips.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent)
            }
            error != null && trips.isEmpty() -> JourneyEmpty(
                Icons.Filled.Timeline, "Couldn't load trips", error ?: "Unknown error",
            )
            trips.isEmpty() -> JourneyEmpty(
                Icons.Filled.Route, "No trips yet",
                "Your recent drives — distance, energy and duration — will show up here.",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trips, key = { "${it.reportTime}-${it.tripId}" }) { t -> TripCard(t, cfg.distanceUnit) }
            }
        }
    }
}

@Composable
private fun TripCard(t: JourneyTrip, distanceUnit: String) {
    CockpitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Route, null, tint = Brand.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtDate(t.startTime), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${fmtClock(t.startTime)} – ${fmtClock(t.endTime)}", color = Brand.muted, fontSize = 12.5.sp)
            }
            Text(distanceLabel(t.distanceKm, distanceUnit), fontWeight = FontWeight.Bold,
                fontSize = 16.sp, color = Brand.accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Duration", fmtDuration(t.durationMs))
            Stat("Avg speed", t.avgSpeedKmh?.let { "$it km/h" } ?: "—")
            Stat("Odometer", t.endOdometer?.let { "$it km" } ?: "—")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Consumption", t.electricConsumption?.let { trimNum(it) } ?: "—")
            Stat("Regen", t.electricRegeneration?.let { trimNum(it) } ?: "—")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Brand.faint, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun JourneyEmpty(icon: ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand.faint, modifier = Modifier.size(54.dp))
            Spacer(Modifier.size(14.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.size(6.dp))
            Text(subtitle, color = Brand.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// ---- formatting helpers (shared by the UI and the CSV export) ----

private fun distanceLabel(km: Int?, unit: String): String =
    if (km == null) "—" else Units.distance(km.toDouble(), unit)

/** Trip distance as a bare number in the chosen unit (for a numeric CSV cell). */
private fun distanceValue(km: Int?, unit: String): String =
    if (km == null) "" else "%.0f".format(Units.distanceValue(km.toDouble(), unit))

private fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)

private fun fmtDate(ms: Long?): String =
    ms?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun fmtClock(ms: Long?): String =
    ms?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun fmtDuration(ms: Long?): String {
    if (ms == null) return "—"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Build a spreadsheet-friendly CSV: a header row then one row per trip. Fields are
 * quote-escaped so locale date/time separators never break the columns. The distance
 * column follows the app's chosen unit (km/mi); the header names the unit.
 */
private fun buildCsv(trips: List<JourneyTrip>, distanceUnit: String): String {
    val distHeader = "Distance (${Units.distanceSuffix(distanceUnit)})"
    val sb = StringBuilder()
    sb.append(row("Date", "Start", "End", distHeader, "Avg consumption", "Energy regen", "Duration", "Odometer (km)"))
    for (t in trips) {
        sb.append(
            row(
                fmtDate(t.startTime),
                fmtClock(t.startTime),
                fmtClock(t.endTime),
                distanceValue(t.distanceKm, distanceUnit),
                t.electricConsumption?.let { trimNum(it) } ?: "",
                t.electricRegeneration?.let { trimNum(it) } ?: "",
                fmtDuration(t.durationMs),
                t.endOdometer?.toString() ?: "",
            ),
        )
    }
    return sb.toString()
}

private fun row(vararg cells: String): String =
    cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n"
