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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openzeekr.app.Deps
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.remote.CallResult
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

        SectionCard("The six extracted secrets") {
            Field("hmac_access_key", cfg.hmacAccessKey, secret = true) { v -> set { it.copy(hmacAccessKey = v) } }
            Field("hmac_secret_key", cfg.hmacSecretKey, secret = true) { v -> set { it.copy(hmacSecretKey = v) } }
            Field("password_public_key", cfg.passwordPublicKey, secret = true) { v -> set { it.copy(passwordPublicKey = v) } }
            Field("prod_secret (X-SIGNATURE key)", cfg.prodSecret, secret = true) { v -> set { it.copy(prodSecret = v) } }
            Field("vin_key", cfg.vinKey, secret = true) { v -> set { it.copy(vinKey = v) } }
            Field("vin_iv", cfg.vinIv, secret = true) { v -> set { it.copy(vinIv = v) } }
        }

        SectionCard("Account & vehicle") {
            Field("email", cfg.email) { v -> set { it.copy(email = v) } }
            Field("password", cfg.password, secret = true) { v -> set { it.copy(password = v) } }
            Field("VIN", cfg.vin) { v -> set { it.copy(vin = v) } }
            Field("access token (optional, skips login)", cfg.accessToken, secret = true) { v -> set { it.copy(accessToken = v) } }
        }

        SectionCard("Endpoint & signing") {
            Field("base URL", cfg.baseUrl) { v -> set { it.copy(baseUrl = v) } }
            Field("sign algo (sha1 / sha256)", cfg.signAlgo) { v -> set { it.copy(signAlgo = v) } }
            Field("app id", cfg.appId) { v -> set { it.copy(appId = v) } }
            Field("client id", cfg.clientId) { v -> set { it.copy(clientId = v) } }
            Field("device model", cfg.deviceModel) { v -> set { it.copy(deviceModel = v) } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                store.replace(cfg)
                deps.onEndpointChanged()
                status = "Saved."
            }) { Text("Save") }

            OutlinedButton(onClick = {
                scope.launch {
                    status = "Logging in…"
                    store.replace(cfg)
                    status = when (val r = deps.auth.login()) {
                        is CallResult.Ok -> { cfg = store.current(); "Login ✓ token stored" }
                        is CallResult.Err -> "Login ✗ ${r.message}"
                    }
                }
            }) { Text("Login") }
        }

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
