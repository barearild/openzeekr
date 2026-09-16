package com.openzeekr.app.ble

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Approach-unlock / walk-away-lock driven by BLE RSSI, with distance + trend hysteresis.
 *
 * Rides the live keep-alive session (see [ProximityService]); it reads the connected-GATT RSSI
 * and never owns the connection. Connected RSSI is the accurate near-field ranging source.
 *
 * Decision model (the tuning the user asked for):
 *  - **Latch**: once we auto-unlock we set [armedUnlocked] and won't unlock again until a lock
 *    happens — so no repeated unlock spam while you stand at the car.
 *  - **Trend**: unlock only while *approaching* (RSSI rising), lock only while *receding* — a flat
 *    signal (you're parked next to it, or the app just launched at the car) does nothing.
 *  - **Hysteresis**: unlock at/above [ConfigStore.sensitivityUnlockRssi] (≈ near), lock at/below
 *    [ConfigStore.sensitivityLockRssi] (≈ farther). The gap between them stops flapping.
 *  - **Cooldown**: after any action, ignore new triggers for [ACTION_COOLDOWN_MS].
 *  - **Adaptive cadence**: 200 ms burst polling while near a threshold or moving (cheap over an
 *    already-open link), 2 s when solidly far/near and steady (low power).
 *
 * On link loss we distinguish an ACCIDENTAL drop (last sample near / not receding → keep armed,
 * let the keep-alive reconnect, don't lock) from a WALK-AWAY (last sample receding or already far
 * → lock after [LINK_LOSS_LOCK_DELAY_MS] if it doesn't come back).
 *
 * The RSSI→metre estimate is for display/logging only (log-distance path loss with a nominal
 * [TX_POWER_1M]/[PATH_LOSS_N] — calibrate at the car). Decisions use RSSI thresholds directly,
 * which are what the single sensitivity knob tunes.
 */
class ProximityController(
    @Suppress("UNUSED_PARAMETER") appContext: android.content.Context,
    private val store: ConfigStore,
    private val lock: DkLockController,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
) {
    enum class Zone { UNKNOWN, FAR, NEAR }
    enum class Phase { PASSIVE, CONNECTING, MONITORING }
    enum class Source { NONE, GATT }

    data class State(
        val running: Boolean = false,
        val phase: Phase = Phase.PASSIVE,
        val rawRssi: Int? = null,
        val smoothedRssi: Int? = null,
        val distanceM: Double? = null,
        val source: Source = Source.NONE,
        val zone: Zone = Zone.UNKNOWN,
        val lastAction: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // Smoothing + trend.
    private var gattEma: Double? = null
    @Volatile private var nextIntervalMs = MONITOR_MID_MS

    // Hysteresis latch: true once we've auto-unlocked (next auto action is a lock).
    private var armedUnlocked = false

    private var monitorJob: Job? = null

    // ---- action gate ----
    private var lastTriggerMs = 0L
    @Volatile private var actionInFlight = false

    // ---- link-loss handling ----
    private var linkLostAtMs = 0L
    private var lostReceding = false
    private var lostRssi: Int? = null
    private var walkAwayArmed = false

    // ---------------- lifecycle ----------------

    fun start() {
        if (_state.value.running) return
        gattEma = null; nextIntervalMs = MONITOR_MID_MS
        linkLostAtMs = 0L; walkAwayArmed = false; lostReceding = false; lostRssi = null
        // Keep armedUnlocked as-is across start/stop toggles within a session isn't meaningful;
        // reset so a fresh monitor starts from a known state.
        armedUnlocked = false
        _state.value = State(running = true, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
        Logx.d("prox", "monitor start (distance+trend hysteresis; rides keep-alive session)")
        monitorJob = scope.launch {
            while (isActive) {
                when (ble.state.value) {
                    DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED -> {
                        if (linkLostAtMs != 0L) { linkLostAtMs = 0L; walkAwayArmed = false }
                        val rssi = ble.pollRemoteRssi()
                        if (rssi != null) onSample(rssi)
                        else Logx.d("prox", "connected but RSSI read returned null")
                    }
                    else -> onSessionDown()
                }
                delay(nextIntervalMs)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel(); monitorJob = null
        gattEma = null; linkLostAtMs = 0L; walkAwayArmed = false
        _state.value = _state.value.copy(running = false, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
    }

    // ---------------- no live session ----------------

    private fun onSessionDown() {
        val now = System.currentTimeMillis()
        if (linkLostAtMs == 0L) {
            linkLostAtMs = now
            // Decide the nature of the drop from the LAST good sample.
            walkAwayArmed = armedUnlocked && (lostReceding || (lostRssi ?: -999) <= store.current().sensitivityLockRssi)
            Logx.d("prox", "link down (lastRssi=$lostRssi receding=$lostReceding armed=$armedUnlocked) " +
                "-> ${if (walkAwayArmed) "arming walk-away lock" else "treating as accidental (keep armed, wait for reconnect)"}")
            gattEma = null
            _state.value = _state.value.copy(
                phase = Phase.PASSIVE, source = Source.NONE, zone = Zone.UNKNOWN,
                rawRssi = null, smoothedRssi = null, distanceM = null,
            )
        } else if (walkAwayArmed && now - linkLostAtMs >= LINK_LOSS_LOCK_DELAY_MS) {
            walkAwayArmed = false
            armedUnlocked = false
            Logx.d("prox", "walk-away confirmed (link down ${LINK_LOSS_LOCK_DELAY_MS}ms) -> lock")
            trigger("walk-away-lock") { lock.lock() }
        }
        nextIntervalMs = MONITOR_MID_MS
    }

    // ---------------- RSSI → distance → decision ----------------

    private fun onSample(rssi: Int) {
        val cfg = store.current()
        val unlockThresh = cfg.sensitivityUnlockRssi
        val lockThresh = cfg.sensitivityLockRssi

        val prev = gattEma
        val delta = if (prev != null) rssi - prev else 0.0
        val jumping = kotlin.math.abs(delta) >= JUMP_DB
        val alpha = if (nextIntervalMs <= MONITOR_FAST_MS || jumping) ALPHA_FAST else ALPHA_SLOW
        val smoothedD = prev?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        gattEma = smoothedD
        val smoothed = smoothedD.toInt()
        val dist = rssiToDistance(smoothed)

        // Trend from the smoothed value vs the previous smoothed value.
        val trend = if (prev != null) smoothedD - prev else 0.0
        val approaching = trend > TREND_DEADBAND        // RSSI rising = getting closer
        val receding = trend < -TREND_DEADBAND
        lostReceding = receding; lostRssi = smoothed    // remembered for link-loss classification

        val prevZone = _state.value.zone
        val zone = when {
            smoothed >= unlockThresh -> Zone.NEAR
            smoothed <= lockThresh -> Zone.FAR
            else -> prevZone
        }
        _state.value = _state.value.copy(
            phase = Phase.MONITORING, source = Source.GATT,
            rawRssi = rssi, smoothedRssi = smoothed, distanceM = dist, zone = zone, error = null,
        )

        // Next cadence: burst while near a threshold or moving; slow when solidly far/near & steady.
        nextIntervalMs = when {
            jumping || approaching || receding -> MONITOR_FAST_MS
            smoothed in (lockThresh - WATCH_MARGIN_DB)..(unlockThresh + WATCH_MARGIN_DB) -> MONITOR_FAST_MS
            else -> MONITOR_SLOW_MS
        }

        Logx.d("prox", "rssi=$rssi ema=$smoothed ~${"%.1f".format(dist)}m " +
            "trend=${"%+.1f".format(trend)} zone=$zone armed=$armedUnlocked " +
            "thr(u/l)=$unlockThresh/$lockThresh next=${nextIntervalMs}ms")

        val cooling = lastTriggerMs != 0L && System.currentTimeMillis() - lastTriggerMs < ACTION_COOLDOWN_MS
        if (cooling || actionInFlight) return

        // UNLOCK: near enough AND approaching (or a clean FAR->NEAR crossing), and not already unlocked.
        if (!armedUnlocked && smoothed >= unlockThresh && (approaching || prevZone == Zone.FAR)) {
            armedUnlocked = true
            Logx.d("prox", "approach-unlock (rssi=$smoothed ~${"%.1f".format(dist)}m)")
            trigger("approach-unlock") { approachUnlock() }
            return
        }
        // LOCK: far enough AND receding, and currently unlocked-by-us.
        if (armedUnlocked && smoothed <= lockThresh && receding) {
            armedUnlocked = false
            Logx.d("prox", "walk-away-lock (rssi=$smoothed ~${"%.1f".format(dist)}m)")
            trigger("walk-away-lock") { lock.lock() }
        }
    }

    /**
     * Approach-unlock with a ONE-SHOT BT-reset retry. The car's GATT link occasionally wedges — a
     * stale session that completed the handshake but then rejects the control frame (the "took the
     * phone out, unlock errored, had to toggle Bluetooth" symptom). A full teardown + fresh connect
     * clears it. This retry is intentionally scoped to the AUTOMATIC approach flow only; a manual
     * button press (VehicleScreen/ControlsScreen/watch) stays a single attempt so the user isn't
     * left waiting on a silent reconnect and can just tap again.
     */
    private suspend fun approachUnlock(): Boolean {
        if (runCatching { lock.unlock() }.getOrDefault(false)) return true
        Logx.w("prox", "approach-unlock failed — resetting the BLE link and retrying once")
        ble.disconnect()
        // connect() is a no-op unless the manager is IDLE/ERROR, so let the teardown settle first.
        awaitState(setOf(DkBleManager.State.IDLE, DkBleManager.State.ERROR), RESET_SETTLE_MS)
        ble.connect(null) // scan-based reconnect (the car uses a resolvable private address)
        if (!awaitState(setOf(DkBleManager.State.SESSION_READY), RESET_RECONNECT_MS)) {
            Logx.w("prox", "reset: session not ready in ${RESET_RECONNECT_MS}ms — giving up retry")
            return false
        }
        return runCatching { lock.unlock() }.getOrDefault(false).also {
            Logx.d("prox", "approach-unlock retry after BT reset -> ${if (it) "ok" else "still failed"}")
        }
    }

    /** Suspend until [ble] state is one of [targets] or [timeoutMs] elapses; true if it reached one. */
    private suspend fun awaitState(targets: Set<DkBleManager.State>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ble.state.value in targets) return true
            delay(120)
        }
        return ble.state.value in targets
    }

    /** Log-distance path loss: d = 10^((txPower@1m − rssi)/(10·n)). Display/log only — calibrate. */
    private fun rssiToDistance(rssi: Int): Double =
        10.0.pow((TX_POWER_1M - rssi) / (10.0 * PATH_LOSS_N))

    // ---------------- action gate ----------------

    private fun trigger(label: String, action: suspend () -> Boolean) {
        if (actionInFlight) { _state.value = _state.value.copy(lastAction = "$label · busy"); return }
        actionInFlight = true
        lastTriggerMs = System.currentTimeMillis()
        scope.launch {
            val result = runCatching { action() }
            actionInFlight = false
            val ok = result.getOrNull() == true
            _state.value = _state.value.copy(
                lastAction = label + (if (ok) " ✓" else " ✗ ${result.exceptionOrNull()?.message ?: "failed"}"),
            )
            Logx.d("prox", "$label result=${if (ok) "ok" else "FAIL ${result.exceptionOrNull()?.message ?: ""}"}")
        }
    }

    companion object {
        private const val ACTION_COOLDOWN_MS = 5_000L
        private const val MONITOR_FAST_MS = 200L
        private const val MONITOR_MID_MS = 800L
        private const val MONITOR_SLOW_MS = 2_000L
        private const val WATCH_MARGIN_DB = 6
        private const val JUMP_DB = 4
        private const val TREND_DEADBAND = 0.6      // dB of smoothed change to count as moving
        private const val ALPHA_FAST = 0.6
        private const val ALPHA_SLOW = 0.35
        private const val LINK_LOSS_LOCK_DELAY_MS = 5_000L
        // Approach-unlock BT-reset retry: time to let a teardown settle to IDLE, and to wait for
        // the fresh session to come up before the second (final) unlock attempt.
        private const val RESET_SETTLE_MS = 1_500L
        private const val RESET_RECONNECT_MS = 15_000L

        // Nominal RSSI-at-1m and path-loss exponent for the metre estimate (DISPLAY ONLY).
        private const val TX_POWER_1M = -59
        private const val PATH_LOSS_N = 2.5
    }
}
