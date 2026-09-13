package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.openzeekr.app.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Approach-unlock / walk-away-lock driven by BLE RSSI.
 *
 * This is the phone-side policy variant (we decide, then issue an explicit DK
 * command) rather than the car-side DKB switch. The RANGING here is real — a BLE
 * scan reads live RSSI — only the resulting [DkLockController] call is a
 * placeholder until the DK session is reversed.
 *
 * Zone machine with hysteresis:
 *   FAR --(smoothed RSSI >= unlockRssi)--> NEAR   => unlock() once
 *   NEAR --(smoothed RSSI <= lockRssi)--> FAR     => lock() once
 *   NEAR + no advertisement for proximityLostMs   => FAR (walked away) => lock()
 * RSSI between the two thresholds keeps the current zone (no flapping).
 */
class ProximityController(
    private val appContext: Context,
    private val store: ConfigStore,
    private val lock: DkLockController,
    private val scope: CoroutineScope,
) {
    enum class Zone { UNKNOWN, FAR, NEAR }

    data class State(
        val running: Boolean = false,
        val rawRssi: Int? = null,
        val smoothedRssi: Int? = null,
        val zone: Zone = Zone.UNKNOWN,
        val lastAction: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Exponential moving average so a single stray reading can't trigger.
    private var ema: Double? = null
    private val alpha = 0.4
    private var lastSeenMs = 0L
    private var watchdog: Job? = null

    // ---- burst guard ----
    /** Wall-clock of the last action we actually fired (for the cooldown gate). */
    private var lastTriggerMs = 0L
    /** True while an action is being sent, so overlapping triggers don't stack. */
    @Volatile private var actionInFlight = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val cfg = store.current()
            val mac = cfg.proximityDeviceMac.trim()
            if (mac.isNotEmpty() && !result.device.address.equals(mac, ignoreCase = true)) return
            lastSeenMs = System.currentTimeMillis()
            onRssi(result.rssi, cfg.unlockRssi, cfg.lockRssi)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = _state.value.copy(error = "scan failed: $errorCode", running = false)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { _state.value = _state.value.copy(error = "Bluetooth off/unavailable"); return }
        val cfg = store.current()
        ema = null
        lastSeenMs = System.currentTimeMillis()
        _state.value = State(running = true, zone = Zone.UNKNOWN)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = cfg.proximityDeviceMac.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(ScanFilter.Builder().setDeviceAddress(it).build()) }

        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { _state.value = _state.value.copy(error = it.message, running = false); return }

        startWatchdog()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        watchdog?.cancel(); watchdog = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _state.value = _state.value.copy(running = false)
    }

    private fun onRssi(rssi: Int, unlockThresh: Int, lockThresh: Int) {
        val next = ema?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        ema = next
        val smoothed = next.toInt()
        val prevZone = _state.value.zone

        val newZone = when {
            smoothed >= unlockThresh -> Zone.NEAR
            smoothed <= lockThresh -> Zone.FAR
            else -> prevZone // hysteresis band
        }

        _state.value = _state.value.copy(rawRssi = rssi, smoothedRssi = smoothed, zone = newZone, error = null)

        if (newZone != prevZone) {
            when (newZone) {
                Zone.NEAR -> if (prevZone != Zone.UNKNOWN) trigger("approach-unlock") { lock.unlock() }
                Zone.FAR -> if (prevZone == Zone.NEAR) trigger("walk-away-lock") { lock.lock() }
                else -> {}
            }
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(2000)
                val cfg = store.current()
                if (_state.value.zone == Zone.NEAR &&
                    System.currentTimeMillis() - lastSeenMs > cfg.proximityLostMs
                ) {
                    _state.value = _state.value.copy(zone = Zone.FAR)
                    trigger("walk-away-lock (signal lost)") { lock.lock() }
                }
            }
        }
    }

    private fun trigger(label: String, action: suspend () -> Boolean) {
        // Burst guard: never overlap actions, and enforce a cooldown between them so
        // RSSI flapping across the threshold (or a signal-loss/return cycle) can't
        // machine-gun lock/unlock at the car.
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
        }
    }

    companion object {
        /** Minimum spacing between auto lock/unlock actions (anti-burst). */
        private const val ACTION_COOLDOWN_MS = 8_000L
    }
}
