package com.openzeekr.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Single source of truth for [SecretsConfig], persisted in
 * EncryptedSharedPreferences. Nothing sensitive is ever compiled in.
 *
 * Supports import/export of the exact `zeekr_secrets.json` shape so an existing
 * dump can be loaded, and the current config saved back out.
 */
class ConfigStore private constructor(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<SecretsConfig> = _config.asStateFlow()

    private fun load(): SecretsConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return ensureDeviceId(SecretsConfig())
        return runCatching { json.decodeFromString<SecretsConfig>(raw) }
            .getOrElse { SecretsConfig() }
            .let(::ensureDeviceId)
    }

    /** Guarantee a stable, app-generated device id (our own, not the OEM's). */
    private fun ensureDeviceId(cfg: SecretsConfig): SecretsConfig =
        if (cfg.deviceIdentifier.isBlank())
            cfg.copy(deviceIdentifier = "OZ-" + UUID.randomUUID().toString().replace("-", "").take(24))
        else cfg

    fun current(): SecretsConfig = _config.value

    fun update(transform: (SecretsConfig) -> SecretsConfig) {
        val next = ensureDeviceId(transform(_config.value))
        persist(next)
    }

    fun replace(cfg: SecretsConfig) = persist(ensureDeviceId(cfg))

    private fun persist(cfg: SecretsConfig) {
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(SecretsConfig.serializer(), cfg)).apply()
        _config.value = cfg
    }

    /** Import a JSON blob (zeekr_secrets.json shape). Unknown keys are ignored;
     *  present keys overwrite, absent keys keep their current value. */
    fun importJson(text: String): Result<Unit> = runCatching {
        val incoming = json.decodeFromString<SecretsConfig>(text)
        // Merge: only overwrite fields that are non-blank in the incoming doc.
        val cur = _config.value
        val merged = cur.copy(
            hmacAccessKey = incoming.hmacAccessKey.ifBlank { cur.hmacAccessKey },
            hmacSecretKey = incoming.hmacSecretKey.ifBlank { cur.hmacSecretKey },
            passwordPublicKey = incoming.passwordPublicKey.ifBlank { cur.passwordPublicKey },
            prodSecret = incoming.prodSecret.ifBlank { cur.prodSecret },
            vinKey = incoming.vinKey.ifBlank { cur.vinKey },
            vinIv = incoming.vinIv.ifBlank { cur.vinIv },
            email = incoming.email.ifBlank { cur.email },
            password = incoming.password.ifBlank { cur.password },
            vin = incoming.vin.ifBlank { cur.vin },
            accessToken = incoming.accessToken.ifBlank { cur.accessToken },
        )
        persist(merged)
    }

    fun exportJson(): String = json.encodeToString(SecretsConfig.serializer(), _config.value)

    companion object {
        private const val FILE = "openzeekr_secure_config"
        private const val KEY_CONFIG = "config_json"

        @Volatile private var INSTANCE: ConfigStore? = null

        fun get(context: Context): ConfigStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        private fun build(appCtx: Context): ConfigStore {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appCtx,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return ConfigStore(prefs)
        }
    }
}
