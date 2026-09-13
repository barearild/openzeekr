package com.openzeekr.app.config

import com.openzeekr.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * All runtime configuration for the app.
 *
 * IMPORTANT: no secret is hardcoded in source. Values may be BAKED at build time
 * from a gitignored `secrets.properties` (exposed via BuildConfig and seeded by
 * [ConfigStore.seedFromBuildDefaults] on first run) so local builds are
 * preconfigured while the repo stays clean; at runtime they live only in
 * EncryptedSharedPreferences, or are imported/exported as JSON. The JSON field
 * names mirror the existing `zeekr_secrets.json` so a dump imports verbatim.
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
    /** HF/xchanger (ECARX) HMAC-SHA1 signing key = NativeSecretLib.getTSPSecretValue("EU","ONLINE"). */
    @SerialName("xchanger_sign_secret") val xchangerSignSecret: String = "",

    // ---- account / vehicle ----
    val email: String = "",
    val password: String = "",
    val vin: String = "",
    /** Numeric account id (IOVContext.getUserId), Frida-confirmed. */
    val userId: String = "",
    /** A pre-captured bearer/access token, if you already have one (skips login). */
    val accessToken: String = "",
    /** xchanger/ECARX DK-backend session (from login step 4b) — DK stack authenticates with these. */
    val xchangerToken: String = "",
    val xchangerClientId: String = "",

    // ---- endpoint / environment ----
    /** EU TSP gateway by default (see dk-real-eu-api notes). */
    val baseUrl: String = "https://eu-snc-tsp-api-gw.zeekrlife.com",
    /** TSP remote-control signs with HMAC-SHA256 (key = prod_secret). */
    val signAlgo: String = "sha256",
    val regionCode: String = "EU",
    val countryCode: String = "SE",
    /** X-PROJECT-ID the DK/TSP gateway validates (EU→ZEEKR_EU). */
    val projectId: String = "ZEEKR_EU",

    // ---- device headers (plain, server-logged, none attested) ----
    val appId: String = "ZEEKRCNCH001M0001",
    val clientId: String = "",
    val deviceModel: String = "Pixel 8",
    val deviceBrand: String = "google",
    val deviceManufacture: String = "Google",
    /** Our own stable device id. Generated once by ConfigStore if blank. */
    val deviceIdentifier: String = "",
    /** App-instance UUID for the X-DEVICE-ID header + app/hb online heartbeat (stock sends a
     *  UUID here, NOT the DK deviceId). Generated once by ConfigStore if blank. */
    val appInstanceId: String = "",
    /** DIAGNOSTIC override for the DK deviceId. When set, openzeekr provisions/pairs AS this
     *  deviceId instead of its own random one — e.g. paste the stock phone's getDeviceID to test
     *  pairing with the exact device the car already knows. Blank = use our own. */
    val dkDeviceId: String = "",
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

    companion object {
        /**
         * Initial config seeded from the gitignored `secrets.properties` via
         * BuildConfig. Every value is empty when that file is absent (fresh
         * clone / CI), so the app just starts blank and is set up in Settings.
         */
        fun fromBuildDefaults(): SecretsConfig = SecretsConfig(
            hmacAccessKey = BuildConfig.SEC_HMAC_ACCESS_KEY,
            hmacSecretKey = BuildConfig.SEC_HMAC_SECRET_KEY,
            passwordPublicKey = BuildConfig.SEC_PASSWORD_PUBLIC_KEY,
            prodSecret = BuildConfig.SEC_PROD_SECRET,
            vinKey = BuildConfig.SEC_VIN_KEY,
            vinIv = BuildConfig.SEC_VIN_IV,
            xchangerSignSecret = BuildConfig.SEC_XCHANGER_SIGN_SECRET,
            // NOTE: email / password / vin / userId are intentionally NOT baked
            // in (see build.gradle.kts). They start blank and are entered on the
            // Settings screen, then persisted only in encrypted on-device prefs.
        )
    }
}
