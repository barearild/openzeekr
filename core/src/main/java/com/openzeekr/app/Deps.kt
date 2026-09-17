package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ble.PhoneStatusProvider
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.remote.AuthRepository
import com.openzeekr.app.remote.CapabilityHolder
import com.openzeekr.app.remote.InboxRepository
import com.openzeekr.app.remote.JourneyRepository
import com.openzeekr.app.remote.NavRepository
import com.openzeekr.app.remote.RemoteControlRepository
import com.openzeekr.app.remote.SentryRepository
import com.openzeekr.app.remote.VehicleStatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Tiny manual DI container — one instance held by [App]. */
class Deps(context: Context) {
    private val appCtx = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val config: ConfigStore = ConfigStore.get(context)
        .also { com.openzeekr.app.util.Logx.setEnabled(it.current().debugLogging) }
    val apiClient: ApiClient = ApiClient.get(config)

    val auth = AuthRepository(config, apiClient)
    val control = RemoteControlRepository(config, apiClient)
    val sentry = SentryRepository(config, apiClient)
    /** Journey log: trip history (distance / energy / duration) with CSV export. */
    val journey = JourneyRepository(config, apiClient)
    /** Member message center (charging done, abnormal parking, alarms, OTA, …). */
    val inbox = InboxRepository(config, apiClient)
    /** Send-to-car: push a navigation POI to the car (also drives the geo:/nav intent handler). */
    val nav = NavRepository(config, apiClient)
    /** Live vehicle status (foreground poll, no push) — observed by the UI. */
    val vehicleState = VehicleStatusHolder(control, appScope)
    /** Per-VIN supported functions — drives which controls the UI shows. */
    val capabilities = CapabilityHolder(control, appScope)

    val ble: DkBleManager = DkBleManager.get(context)
    // One device id for both the TSP transport (x-device-id) and the DK body,
    // as the stock app does (single getDeviceID). Also arm the BLE session if a
    // credential was already provisioned on a previous run.
    val dkIdentity: DkIdentity = DkIdentity.get(context).also { id ->
        if (config.current().deviceIdentifier != id.deviceId) config.update { it.copy(deviceIdentifier = id.deviceId) }
        id.credential()?.let { ble.setCredential(it) }
    }
    val provisioning = DkProvisioning(config, dkIdentity, ble)
    val lock = DkLockController(ble.session)
    val phoneStatus = PhoneStatusProvider(appCtx)
    val rpa = RpaController(ble.session, appScope, phoneStatus::stateByte, rssi = ble::pollRemoteRssi)
    /** Wakelock-free motion state (still vs moving) for proximity cadence gating. */
    val motion = com.openzeekr.app.ble.MotionMonitor(appCtx)
    val proximity = ProximityController(
        appCtx, config, lock, ble, motion, appScope,
        // Cloud lock fallback for the walk-away lock when BLE won't confirm — never leave the car open.
        cloudLock = { control.send(com.openzeekr.app.remote.Command.LOCK) is com.openzeekr.app.remote.CallResult.Ok },
    )

    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
