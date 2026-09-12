package com.openzeekr.app.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * All runtime configuration for the app.
 *
 * IMPORTANT: none of the six extracted secrets are baked into source. They live
 * only here and are persisted by [ConfigStore] into EncryptedSharedPreferences,
 * or imported/exported as JSON. The JSON field names mirror the existing
 * `zeekr_secrets.json` so an existing dump imports verbatim.
 *
 * The "six secrets" (see reversing notes):
 *   static (from the key extractor, --region EU):
 *     - hmacAccessKey       -> X-api access key id
 *     - hmacSecretKey       -> account/login HMAC secret
 *     - passwordPublicKey   -> RSA pubkey used to encrypt the login password
 *   runtime (Frida-dumped on 3.0.x):
 *     - prodSecret          -> the X-SIGNATURE signing secret (getSignSecret)
 *     - vinKey / vinIv      -> AES key/iv used to encrypt the VIN header
 */
@Serializable
data class SecretsConfig(
    // ---- the six extracted secrets (configurable, never hardcoded) ----
    @SerialName("hmac_access_key") val hmacAccessKey: String = "",
    @SerialName("hmac_secret_key") val hmacSecretKey: String = "",
    @SerialName("password_public_key") val passwordPublicKey: String = "",
    @SerialName("prod_secret") val prodSecret: String = "",
    @SerialName("vin_key") val vinKey: String = "",
    @SerialName("vin_iv") val vinIv: String = "",

    // ---- account / vehicle ----
    val email: String = "",
    val password: String = "",
    val vin: String = "",
    /** A pre-captured bearer/access token, if you already have one (skips login). */
    val accessToken: String = "",

    // ---- endpoint / environment ----
    /** EU TSP gateway by default (see dk-real-eu-api notes). */
    val baseUrl: String = "https://eu-snc-tsp-api-gw.zeekrlife.com",
    /** "sha1" (baselinelibrary SignInterceptor) or "sha256" (snc/TSP stack). */
    val signAlgo: String = "sha1",

    // ---- device headers (plain, server-logged, none attested) ----
    val appId: String = "ZEEKRCNCH001M0001",
    val clientId: String = "",
    val deviceModel: String = "Pixel 9",
    val deviceBrand: String = "google",
    val deviceManufacture: String = "Google",
    /** Our own stable device id. Generated once by ConfigStore if blank. */
    val deviceIdentifier: String = "",
    val agentType: String = "APP",
    val agentVersion: String = "3.0.7",
    val envType: String = "prod",
    val appVersion: String = "3.0.7",
    val sigVersion: String = "1.0",

    // ---- proximity (RSSI-based approach-unlock / walk-away-lock) ----
    val proximityEnabled: Boolean = false,
    /** BLE MAC of the vehicle to range against (blank = strongest advertiser). */
    val proximityDeviceMac: String = "",
    /**
     * Unlock when the smoothed RSSI rises to/above this (dBm). Higher = closer.
     * Default -65 dBm ≈ roughly within ~1–2 m of the car.
     */
    val unlockRssi: Int = -65,
    /**
     * Lock when the smoothed RSSI falls to/below this (dBm). Lower = farther.
     * Default -85 dBm ≈ walking away / edge of reliable range. The gap to
     * [unlockRssi] is deliberate hysteresis so it doesn't flap at the boundary.
     */
    val lockRssi: Int = -85,
    /** If NEAR and no advertisement is seen for this long, treat as walked-away. */
    val proximityLostMs: Long = 8000,
) {
    /** True when the minimum needed to talk to the cloud is present. */
    val cloudReady: Boolean
        get() = baseUrl.isNotBlank() && prodSecret.isNotBlank() &&
            (accessToken.isNotBlank() || (email.isNotBlank() && password.isNotBlank()))

    /** The secret used for X-SIGNATURE. prodSecret per the reversing notes. */
    val signSecret: String get() = prodSecret
}
