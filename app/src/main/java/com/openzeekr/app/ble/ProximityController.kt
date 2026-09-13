package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.config.SecretsConfig
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Approach-unlock / walk-away-lock driven by BLE RSSI, in two power stages:
 *
 *  1. **PASSIVE** — a battery-cheap `SCAN_MODE_LOW_POWER` scan with a *hardware*
 *     [ScanFilter] pinned to the car (by MAC when known, else the DK service UUID).
 *     Advertisement RSSI is coarse and infrequent; we only use it as a trip-wire.
 *     When the smoothed advert RSSI reaches the connect band (≥ [SecretsConfig.CONNECT_RSSI_FAR],
 *     the "-70..-90" the user asked for) we escalate.
 *
 *  2. **MONITORING** — we do a background [DkBleManager.connect] and, once the DK
 *     session is live, poll the *connected-GATT* RSSI aggressively
 *     ([MONITOR_INTERVAL_MS]). Connected RSSI is far more accurate/frequent than
 *     advert RSSI, so the unlock/lock decision rides on it.
 *
 * Thresholds (all derived from the single user knob so they can't overlap):
 *   unlock at smoothed RSSI ≥ [SecretsConfig.effectiveUnlockRssi] (user value, floored at -65)
 *   lock   at smoothed RSSI ≤ [SecretsConfig.effectiveLockRssi]   (= unlock − 5 dB)
 *   RSSI between the two keeps the current zone (hysteresis, no flapping).
 * If the car drifts back below the connect band (or the GATT drops) we release the
 * connection and fall back to PASSIVE to stop burning power sitting connected.
 *
 * This owns the connection lifecycle while enabled; [ProximityService] therefore
 * suspends its always-on keep-alive whenever proximity is on.
 */
class ProximityController(
    private val appContext: Context,
    private val store: ConfigStore,
    private val lock: DkLockController,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
) {
    enum class Zone { UNKNOWN, FAR, NEAR }
    enum class Phase { PASSIVE, CONNECTING, MONITORING }
    enum class Source { NONE, ADVERT, GATT }

    data class State(
        val running: Boolean = false,
        val phase: Phase = Phase.PASSIVE,
        val rawRssi: Int? = null,
        val smoothedRssi: Int? = null,
        val source: Source = Source.NONE,
        val zone: Zone = Zone.UNKNOWN,
        val lastAction: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Exponential moving averages so a single stray reading can't trigger. Kept per
    // stage because advert and connected RSSI live on different scales/cadences.
    private var advEma: Double? = null
    private var gattEma: Double? = null
    private val alpha = 0.4

    private var scanCb: ScanCallback? = null
    private var monitorJob: Job? = null
    private var connectJob: Job? = null

    // ---- burst guard ----
    private var lastTriggerMs = 0L
    @Volatile private var actionInFlight = false
    // Cooldown after a failed/abandoned connect, so we don't hammer connectGatt.
    private var connectBackoffUntilMs = 0L

    // ---------------- lifecycle ----------------

    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.running) return
        if (adapter?.bluetoothLeScanner == null) {
            _state.value = _state.value.copy(error = "Bluetooth off/unavailable"); return
        }
        advEma = null; gattEma = null
        _state.value = State(running = true, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
        startPassiveScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        monitorJob?.cancel(); monitorJob = null
        connectJob?.cancel(); connectJob = null
        stopPassiveScan()
        // If we brought the session up for proximity, drop it so the (now re-armed)
        // keep-alive can decide connection policy from scratch.
        if (_state.value.phase != Phase.PASSIVE) runCatching { ble.disconnect() }
        _state.value = _state.value.copy(running = false, phase = Phase.PASSIVE)
    }

    // ---------------- stage 1: passive low-power scan ----------------

    @SuppressLint("MissingPermission")
    private fun startPassiveScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopPassiveScan()
        advEma = null
        val cfg = store.current()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        // Hardware filter so the radio wakes the app only for our car (cheap, per spec).
        val mac = cfg.proximityDeviceMac.trim()
        val filters = if (mac.isNotEmpty())
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build())
        else
            listOf(ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(DkProtocol.SERVICE_UUID))).build())

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onPassiveAdvert(result)
            override fun onScanFailed(errorCode: Int) {
                _state.value = _state.value.copy(error = "scan failed: $errorCode", running = false)
            }
        }
        scanCb = cb
        _state.value = _state.value.copy(phase = Phase.PASSIVE, source = Source.NONE, error = null)
        Logx.d("prox", "passive scan (LOW_POWER, ${if (mac.isNotEmpty()) "mac=$mac" else "service-uuid"})")
        runCatching { scanner.startScan(filters, settings, cb) }
            .onFailure { _state.value = _state.value.copy(error = it.message, running = false) }
    }

    @SuppressLint("MissingPermission")
    private fun stopPassiveScan() {
        scanCb?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
    }

    private fun onPassiveAdvert(result: ScanResult) {
        if (_state.value.phase != Phase.PASSIVE) return
        val cfg = store.current()
        val mac = cfg.proximityDeviceMac.trim()
        // The hardware filter already narrows this, but double-check when a MAC is set.
        if (mac.isNotEmpty() && !result.device.address.equals(mac, ignoreCase = true)) return

        val next = advEma?.let { it + alpha * (result.rssi - it) } ?: result.rssi.toDouble()
        advEma = next
        val smoothed = next.toInt()
        _state.value = _state.value.copy(rawRssi = result.rssi, smoothedRssi = smoothed, source = Source.ADVERT)

        if (smoothed >= SecretsConfig.CONNECT_RSSI_FAR) beginConnect(smoothed)
    }

    // ---------------- stage 2: background connect + aggressive monitor ----------------

    private fun beginConnect(advRssi: Int) {
        if (_state.value.phase != Phase.PASSIVE) return
        val now = System.currentTimeMillis()
        if (now < connectBackoffUntilMs) return
        if (connectJob?.isActive == true) return

        Logx.d("prox", "advert $advRssi dBm ≥ ${SecretsConfig.CONNECT_RSSI_FAR} — background connect")
        stopPassiveScan()
        gattEma = null
        _state.value = _state.value.copy(phase = Phase.CONNECTING, lastAction = "connecting…")

        connectJob = scope.launch {
            // Reuse the proven connect path (scan-by-name grabs the broadcast-random the
            // DK session needs). If we already have a session up, skip straight to monitor.
            if (ble.state.value != DkBleManager.State.SESSION_READY) {
                runCatching { ble.connect(null) }
            }
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                when (ble.state.value) {
                    DkBleManager.State.SESSION_READY -> { startMonitoring(); return@launch }
                    DkBleManager.State.ERROR, DkBleManager.State.IDLE -> break
                    else -> {}
                }
                delay(400)
            }
            // Timed out or errored — back off and resume passive scan.
            connectBackoffUntilMs = System.currentTimeMillis() + CONNECT_BACKOFF_MS
            _state.value = _state.value.copy(lastAction = "connect timed out", error = ble.lastError)
            if (_state.value.running) startPassiveScan()
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        _state.value = _state.value.copy(phase = Phase.MONITORING, source = Source.GATT, error = null)
        Logx.d("prox", "session ready — aggressive RSSI monitor @ ${MONITOR_INTERVAL_MS}ms")
        var misses = 0
        monitorJob = scope.launch {
            while (isActive) {
                val st = ble.state.value
                if (st != DkBleManager.State.SESSION_READY && st != DkBleManager.State.CONNECTED) {
                    onConnectionLost("session dropped"); return@launch
                }
                val rssi = ble.pollRemoteRssi()
                if (rssi == null) {
                    if (++misses >= MAX_RSSI_MISSES) { onConnectionLost("no RSSI"); return@launch }
                    delay(MONITOR_INTERVAL_MS); continue
                }
                misses = 0
                onGattRssi(rssi)
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private fun onGattRssi(rssi: Int) {
        val cfg = store.current()
        val unlockThresh = cfg.effectiveUnlockRssi
        val lockThresh = cfg.effectiveLockRssi

        val next = gattEma?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        gattEma = next
        val smoothed = next.toInt()
        val prevZone = _state.value.zone

        val newZone = when {
            smoothed >= unlockThresh -> Zone.NEAR
            smoothed <= lockThresh -> Zone.FAR
            else -> prevZone // hysteresis band
        }
        _state.value = _state.value.copy(
            rawRssi = rssi, smoothedRssi = smoothed, source = Source.GATT, zone = newZone, error = null,
        )

        if (newZone != prevZone) {
            when (newZone) {
                // We are already connected here (MONITORING) — so unlock always happens
                // on a live session, never a cold one.
                Zone.NEAR -> if (prevZone != Zone.UNKNOWN) trigger("approach-unlock") { lock.unlock() }
                // Walk-away: lock, then clean-disconnect once the lock write has landed.
                Zone.FAR -> if (prevZone == Zone.NEAR) trigger(
                    "walk-away-lock",
                    onDone = { if (_state.value.running) releaseAndRescan() },
                ) { lock.lock() }
                else -> {}
            }
        }

        // Drifted back past the far edge of the connect band without ever having gone
        // NEAR (so nothing to lock) → release the connection and resume cheap passive
        // scanning. Skipped while an action is in flight so we never tear down the GATT
        // mid lock/unlock write (that path disconnects itself via onDone).
        if (smoothed <= SecretsConfig.CONNECT_RSSI_FAR && !actionInFlight && _state.value.zone != Zone.NEAR) {
            Logx.d("prox", "smoothed $smoothed ≤ ${SecretsConfig.CONNECT_RSSI_FAR} — releasing connection")
            releaseAndRescan()
        }
    }

    private fun onConnectionLost(why: String) {
        Logx.d("prox", "connection lost ($why)")
        // Treat a lost connection while NEAR as walking away.
        if (_state.value.zone == Zone.NEAR) {
            _state.value = _state.value.copy(zone = Zone.FAR)
            trigger("walk-away-lock (signal lost)") { lock.lock() }
        }
        releaseAndRescan()
    }

    @SuppressLint("MissingPermission")
    private fun releaseAndRescan() {
        monitorJob?.cancel(); monitorJob = null
        runCatching { ble.disconnect() }
        gattEma = null
        _state.value = _state.value.copy(phase = Phase.PASSIVE, zone = Zone.UNKNOWN, source = Source.NONE)
        if (_state.value.running) startPassiveScan()
    }

    // ---------------- action gate ----------------

    /**
     * Run a lock/unlock action behind the burst guard. [onDone] runs **after** the
     * action's write completes — used to disconnect cleanly only once the lock has
     * actually gone out (never mid-write, which is what would corrupt the session).
     */
    private fun trigger(label: String, onDone: (suspend () -> Unit)? = null, action: suspend () -> Boolean) {
        // Burst guard: never overlap actions, and enforce a cooldown between them so
        // RSSI flapping across the threshold can't machine-gun lock/unlock at the car.
        if (actionInFlight) {
            _state.value = _state.value.copy(lastAction = "$label · busy")
            return
        }
        val now = System.currentTimeMillis()
        val sinceLast = now - lastTriggerMs
        if (lastTriggerMs != 0L && sinceLast < ACTION_COOLDOWN_MS) {
            _state.value = _state.value.copy(lastAction = "$label ⏳ cooldown (${(ACTION_COOLDOWN_MS - sinceLast) / 1000}s)")
            return
        }
        actionInFlight = true
        lastTriggerMs = now
        scope.launch {
            val result = runCatching { action() }
            actionInFlight = false
            _state.value = _state.value.copy(
                lastAction = label + (result.exceptionOrNull()?.let { " ✗ ${it.message}" } ?: " ✓"),
            )
            onDone?.invoke() // only after the write has completed
        }
    }

    companion object {
        /** Minimum spacing between auto lock/unlock actions (anti-burst). */
        private const val ACTION_COOLDOWN_MS = 8_000L
        /** How often to read the connected-GATT RSSI while monitoring (aggressive). */
        private const val MONITOR_INTERVAL_MS = 800L
        /** Consecutive null RSSI reads before we consider the link dead. */
        private const val MAX_RSSI_MISSES = 5
        /** How long to wait for the DK session after a background connect. */
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** Backoff before retrying a connect that timed out/failed. */
        private const val CONNECT_BACKOFF_MS = 15_000L
    }
}
