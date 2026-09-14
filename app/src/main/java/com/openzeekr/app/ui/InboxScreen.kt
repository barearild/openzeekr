package com.openzeekr.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.InboxMessage
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Member message center — charging done/abnormal, alarm / abnormal parking, remote-control
 * results, low battery, OTA, marketing. Pulled on demand (no push of bodies) from
 * `overseas-app/member/inbox`; tapping a message marks it read and follows its deep-link.
 */
@Composable
fun InboxScreen(deps: Deps, onBack: () -> Unit, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<InboxMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; error = null
        when (val r = deps.inbox.messages(page = 1)) {
            is CallResult.Ok -> { messages = r.value; error = null }
            is CallResult.Err -> error = r.message
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp),
            )
            Text("Messages", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (messages.any { !it.read }) {
                Text(
                    "Mark all read", color = Brand.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        scope.launch {
                            deps.inbox.markAllRead()
                            messages = messages.map { it.copy(read = true) }
                        }
                    }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        when {
            loading && messages.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent)
            }
            error != null && messages.isEmpty() -> InboxEmpty(
                Icons.Filled.NotificationsNone,
                "Couldn't load messages",
                error ?: "Unknown error",
            )
            messages.isEmpty() -> InboxEmpty(
                Icons.Filled.NotificationsNone,
                "No messages",
                "Alerts from your car — charging, security, parking — will show up here.",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages, key = { it.id ?: it.hashCode().toString() }) { m ->
                    MessageRow(m) {
                        // mark read + follow the deep-link if any
                        if (!m.read) {
                            messages = messages.map { if (it.id == m.id) it.copy(read = true) else it }
                            m.id?.let { id -> scope.launch { deps.inbox.markRead(id) } }
                        }
                        val url = m.redirectUrl
                        if (!url.isNullOrBlank()) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { snackbar("Can't open this message's link") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: InboxMessage, onClick: () -> Unit) {
    val (icon, tint) = categoryLook(m)
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.title ?: "Message",
                    fontWeight = if (m.read) FontWeight.SemiBold else FontWeight.Bold,
                    color = if (m.read) Brand.muted else MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (!m.read) {
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Brand.accent))
                }
            }
            m.body?.let {
                Spacer(Modifier.size(3.dp))
                Text(it, color = Brand.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            m.timeMs?.let {
                Spacer(Modifier.size(5.dp))
                Text(relativeTime(it), color = Brand.faint, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun InboxEmpty(icon: ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand.faint, modifier = Modifier.size(54.dp))
            Spacer(Modifier.size(14.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                subtitle, color = Brand.muted, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Category → (icon, colour), inferred from the opaque customTypeId / title keywords. */
private fun categoryLook(m: InboxMessage): Pair<ImageVector, Color> {
    val hay = ((m.category ?: "") + " " + (m.title ?: "")).lowercase()
    return when {
        listOf("charg", "battery", "soc", "energy").any { it in hay } -> Icons.Filled.BatteryChargingFull to Brand.energy
        listOf("alarm", "sentry", "sentinel", "theft", "abnormal", "park", "intru", "security").any { it in hay } ->
            Icons.Filled.Shield to Brand.crit
        listOf("ota", "update", "upgrade", "firmware", "version").any { it in hay } -> Icons.Filled.SystemUpdate to Brand.accent
        listOf("vehicle", "lock", "door", "climate", "window", "control").any { it in hay } -> Icons.Filled.DirectionsCar to Brand.accent
        else -> Icons.Filled.NotificationsNone to Brand.muted
    }
}

/** Compact relative time ("just now", "3h", "2d", else a date). */
private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    if (diff < 0) return "just now"
    val min = diff / 60_000
    val hr = diff / 3_600_000
    val day = diff / 86_400_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        hr < 24 -> "${hr}h ago"
        day < 7 -> "${day}d ago"
        else -> {
            val d = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            d.format(java.util.Date(ms))
        }
    }
}
