package com.openzeekr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(deps: Deps, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val store = deps.config
    var cfg by remember { mutableStateOf(store.current()) }
    var status by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    fun set(update: (SecretsConfig) -> SecretsConfig) { cfg = update(cfg) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Configuration", style = MaterialTheme.typography.titleLarge)
        Text(
            "Secrets are stored only on-device (encrypted) and never compiled in. Import an existing zeekr_secrets.json below, or fill fields manually.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionCard("Extracted app-global secrets") {
            Field("hmac_access_key", cfg.hmacAccessKey, secret = true) { v -> set { it.copy(hmacAccessKey = v) } }
            Field("hmac_secret_key", cfg.hmacSecretKey, secret = true) { v -> set { it.copy(hmacSecretKey = v) } }
            Field("password_public_key", cfg.passwordPublicKey, secret = true) { v -> set { it.copy(passwordPublicKey = v) } }
            Field("prod_secret (TSP X-SIGNATURE key)", cfg.prodSecret, secret = true) { v -> set { it.copy(prodSecret = v) } }
            Field("vin_key", cfg.vinKey, secret = true) { v -> set { it.copy(vinKey = v) } }
            Field("vin_iv", cfg.vinIv, secret = true) { v -> set { it.copy(vinIv = v) } }
            Field("xchanger_sign_secret (DK/HF X-SIGNATURE key)", cfg.xchangerSignSecret, secret = true) { v -> set { it.copy(xchangerSignSecret = v) } }
        }

        val loggedIn = cfg.accessToken.isNotBlank()

        SectionCard("Account") {
            LoginStatusBanner(loggedIn = loggedIn, email = cfg.email, vin = cfg.vin, userId = cfg.userId)
            Field("email", cfg.email) { v -> set { it.copy(email = v) } }
            Field("password", cfg.password, secret = true) { v -> set { it.copy(password = v) } }
            // DIAGNOSTIC: force the DK deviceId (blank = our own random one). Paste the stock
            // phone's getDeviceID to test pairing as the exact device the car already knows.
            Field("dk_device_id override (diagnostic)", cfg.dkDeviceId) { v -> set { it.copy(dkDeviceId = v.trim()) } }
        }
        // Endpoint (EU), signing (SHA1) and device model (Pixel 9) are baked in
        // via SecretsConfig defaults — not user-configurable for now.

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.replace(cfg)
                deps.onEndpointChanged()
                status = "Saved."
            }) { Text("Save") }

            if (loggedIn) {
                OutlinedButton(onClick = {
                    Logx.d("ui", "Logout pressed")
                    store.update { it.copy(accessToken = "", userId = "") }
                    cfg = store.current()
                    deps.onEndpointChanged()
                    status = "Logged out."
                }) { Text("Log out") }
            } else OutlinedButton(onClick = {
                scope.launch {
                    status = "Logging in…"
                    Logx.d("ui", "Login button pressed")
                    runCatching {
                        store.replace(cfg)
                        when (val r = deps.auth.login()) {
                            is CallResult.Ok -> { cfg = store.current(); "Login ✓ token stored" }
                            is CallResult.Err -> "Login ✗ ${r.message}"
                        }
                    }.fold(
                        onSuccess = { status = it },
                        onFailure = { Logx.e("ui", "login button threw", it); status = "Login ✗ ${it.message}" },
                    )
                }
            }) { Text("Login") }
        }

        OutlinedButton(onClick = {
            store.resetToBuildDefaults()
            cfg = store.current()
            deps.onEndpointChanged()
            status = "Reset to build defaults."
        }) { Text("Reset to build defaults") }

        Divider()
        SectionCard("Import / Export JSON") {
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Paste zeekr_secrets.json here") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    status = store.importJson(importText).fold({ cfg = store.current(); "Imported." }, { "Import failed: ${it.message}" })
                }) { Text("Import") }
                OutlinedButton(onClick = { importText = store.exportJson() }) { Text("Export to box") }
            }
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)

        Divider()
        LogViewer()
    }
}

/** Live on-device log (Logx ring buffer). Newest at the bottom; copy/clear. */
@Composable
private fun LogViewer() {
    val lines by Logx.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Logs (${lines.size})", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(Logx.dump())) }) { Text("Copy") }
                    OutlinedButton(onClick = { Logx.clear() }) { Text("Clear") }
                }
            }
            if (lines.isEmpty()) {
                Text("No log yet. Press Login (or Connect) and watch here.", style = MaterialTheme.typography.bodySmall)
            } else {
                // Show the most recent lines (oldest of the shown window first).
                Column {
                    lines.takeLast(120).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Prominent logged-in / logged-out indicator. */
@Composable
private fun LoginStatusBanner(loggedIn: Boolean, email: String, vin: String, userId: String) {
    val bg = if (loggedIn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (loggedIn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (loggedIn) "● Logged in" else "○ Not logged in",
                style = MaterialTheme.typography.titleMedium,
                color = fg,
            )
            if (loggedIn) {
                Text(
                    buildString {
                        append(email.ifBlank { "(no email)" })
                        if (userId.isNotBlank()) append("  ·  userId $userId")
                        if (vin.isNotBlank()) append("  ·  VIN $vin")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            } else {
                Text("Enter email + password below and press Login.", style = MaterialTheme.typography.bodySmall, color = fg)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !secret,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
