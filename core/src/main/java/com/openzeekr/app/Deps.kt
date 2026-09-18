package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ble.CarProximityController
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
        .also {
            com.openzeekr.app.util.Logx.setEnabled(it.current().debugLogging)
            // The experimental CAR-side walk-away auto-lock is hidden/disabled for now. Force the flag
            // off on every launch so a value persisted from a previous build can't keep it active while
            // the UI toggle is gone. Re-enable by restoring the toggle + removing this line.
            if (it.current().carSideAutoLock) it.setCarSideAutoLock(false)
        }
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
    /** FCM push registrar: registers our device token with the message-centre so the car's pushes
     *  (esp. security alarms) arrive when the phone is asleep, instead of only the 5-min inbox poll. */
    val push = com.openzeekr.app.push.PushRegistrar(config, appScope)
    /** Car-side schedules: off-peak charging windows + departure/booking-travel preconditioning. */
    val schedule = com.openzeekr.app.remote.ScheduleRepository(config, apiClient)
    /** Live vehicle status (foreground poll, no push) — observed by the UI. */
    val vehicleState = VehicleStatusHolder(control, appScope)
    /** Per-VIN supported functions — drives which controls the UI shows. */
    val capabilities = CapabilityHolder(control, appScope)

    val ble: DkBleManager = DkBleManager.get(context)
    /** BLE-first, cloud-fallback dispatcher for actions the DK session can actuate directly. */
    val vehicleControl = com.openzeekr.app.remote.VehicleControl(ble, control)
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
        // Cloud lock-state probe for the out-of-range backstop: centralLockingStatus "1"=locked, "0"=unlocked.
        cloudIsLocked = {
            (control.status() as? com.openzeekr.app.remote.CallResult.Ok)?.value
                ?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus
                ?.let { it == "1" }
        },
    )
    /**
     * Car-side walk-away auto-lock — a PARALLEL, redundant safety net that runs ALONGSIDE the always-on
     * phone-side [proximity] (not instead of it). When config.carSideAutoLock is on it uploads RSSI
     * calibration so the car's own firmware can auto-LOCK on walk-away too, and surfaces the car's
     * 0x0159 auto-lock events. Self-gates on the toggle, so starting it here is always safe.
     */
    val carProximity = CarProximityController(config, ble, appScope)
        // NOT started for now — the car-side walk-away lock is hidden/disabled (force-off above). It also
        // self-gates on config.carSideAutoLock, so leaving it unstarted is doubly safe. Re-.start() to revive.

    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
