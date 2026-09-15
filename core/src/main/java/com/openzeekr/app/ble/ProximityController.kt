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
import kotlin.math.abs

/**
 * Approach-unlock / walk-away-lock driven by BLE RSSI.
 *
 * This controller does NOT own the BLE connection. The foreground key service
 * ([ProximityService.keepConnected]) is the sole owner — it holds the DK session
 * connected to the car whenever the phone is provisioned and in range, and reconnects
 * if it drops. This controller simply **rides that live session**: it polls the
 * connected-GATT RSSI, smooths it, and triggers lock/unlock on zone transitions. It
 * never scans, connects, or disconnects — so it can't fight the keep-alive.
 *
 * Responsiveness (daily-driver tuning). Two things make a naive fixed-rate poll feel
 * bad, and both are handled here:
 *
 *  1. **Adaptive cadence.** Polling connected-GATT RSSI at a flat rate is either too slow
 *     to react or wastes power. Instead we poll SLOW (2 s) while you're solidly far away
 *     *or* solidly parked-at-the-car, and switch to a 250 ms BURST the moment the smoothed
 *     RSSI enters the ±[WATCH_MARGIN_DB] dB band around either threshold (i.e. you're
 *     approaching or leaving) or jumps sharply. So the crossing that actually triggers a
 *     lock/unlock is sampled fast, and the idle case stays low-power.
 *  2. **Adaptive smoothing.** The EMA uses a lighter alpha during a burst so it tracks your
 *     approach in well under a second, and a heavier alpha when idle so a stray reading
 *     can't flap the zone.
 *
 * Walk-away lock is driven by TWO signals, because RSSI is least trustworthy exactly at the
 * fringe where you leave:
 *   - smoothed RSSI falling to ≤ sensitivityLockRssi (a clean walk-away while still linked), OR
 *   - the DK session dropping while you were NEAR and staying down for [LINK_LOSS_LOCK_DELAY_MS]
 *     (the keep-alive couldn't get it back = you're genuinely gone). If it recovers in that
 *     window (you were just standing next to the car through a transient drop) the pending lock
 *     is cancelled, so we never lock-then-unlock.
 *
 * Thresholds come from the single user sensitivity knob:
 *   unlock at smoothed RSSI ≥ [ConfigStore] sensitivityUnlockRssi
 *   lock   at smoothed RSSI ≤ sensitivityLockRssi  (unlock − 8 dB)
 *   RSSI between the two keeps the current zone (hysteresis, no flapping).
 */
class ProximityController(
    @Suppress("UNUSED_PARAMETER") appContext: android.content.Context,
    private val store: ConfigStore,
    private val lock: DkLockController,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
) {
    enum class Zone { UNKNOWN, FAR, NEAR }

    /** Kept for UI compatibility. With the keep-alive owning the link we only ever report
     *  MONITORING (session live, reading RSSI) or PASSIVE (waiting for the service to connect). */
    enum class Phase { PASSIVE, CONNECTING, MONITORING }
    enum class Source { NONE, GATT }

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

    // Exponential moving average so a single stray reading can't trip a zone change.
    // Alpha is chosen per-sample: light (fast) during a burst, heavy (stable) when idle.
    private var gattEma: Double? = null

    // Cadence chosen for the NEXT poll from the last smoothed reading.
    @Volatile private var nextIntervalMs = MONITOR_MID_MS

    private var monitorJob: Job? = null

    // ---- burst guard ----
    private var lastTriggerMs = 0L
    @Volatile private var actionInFlight = false

    // ---- link-loss walk-away lock ----
    // Captured at the moment the live session drops so we can lock if it stays down.
    private var wasNearAtLoss = false
    private var linkLostAtMs = 0L
    private var walkAwayLockArmed = false

    // ---------------- lifecycle ----------------

    fun start() {
        if (_state.value.running) return
        gattEma = null
        nextIntervalMs = MONITOR_MID_MS
        wasNearAtLoss = false; linkLostAtMs = 0L; walkAwayLockArmed = false
        _state.value = State(running = true, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
        Logx.d("prox", "monitor started (rides the keep-alive session; adaptive cadence + link-loss lock)")
        monitorJob = scope.launch {
            while (isActive) {
                when (ble.state.value) {
                    DkBleManager.State.SESSION_READY, DkBleManager.State.CONNECTED -> {
                        // Link is up: any pending "you walked away" state is void — you're here.
                        if (linkLostAtMs != 0L) { linkLostAtMs = 0L; wasNearAtLoss = false; walkAwayLockArmed = false }
                        val rssi = ble.pollRemoteRssi()
                        if (rssi != null) onGattRssi(rssi)
                    }
                    else -> onSessionDown()
                }
                delay(nextIntervalMs)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel(); monitorJob = null
        gattEma = null
        linkLostAtMs = 0L; wasNearAtLoss = false; walkAwayLockArmed = false
        // Deliberately does NOT disconnect — the keep-alive owns the connection.
        _state.value = _state.value.copy(running = false, phase = Phase.PASSIVE, zone = Zone.UNKNOWN)
    }

    // ---------------- no live session ----------------

    private fun onSessionDown() {
        val now = System.currentTimeMillis()
        if (linkLostAtMs == 0L) {
            // First loop after the session dropped. Remember whether we were at the car, then
            // reset the smoothing/zone so a recovery re-evaluates from scratch.
            linkLostAtMs = now
            wasNearAtLoss = _state.value.zone == Zone.NEAR
            walkAwayLockArmed = wasNearAtLoss
            gattEma = null
            _state.value = _state.value.copy(
                phase = Phase.PASSIVE, source = Source.NONE, zone = Zone.UNKNOWN,
                rawRssi = null, smoothedRssi = null,
            )
        } else if (walkAwayLockArmed && now - linkLostAtMs >= LINK_LOSS_LOCK_DELAY_MS) {
            // Session was NEAR and the keep-alive still can't get it back — you're gone. Lock.
            walkAwayLockArmed = false
            Logx.d("prox", "link lost while near for ${LINK_LOSS_LOCK_DELAY_MS}ms — walk-away lock")
            trigger("walk-away-lock (link lost)") { lock.lock() }
        }
        // While waiting to reconnect, poll at a modest rate so the loss timer stays responsive.
        nextIntervalMs = MONITOR_MID_MS
    }

    // ---------------- RSSI → zone → action ----------------

    private fun onGattRssi(rssi: Int) {
        val cfg = store.current()
        val unlockThresh = cfg.sensitivityUnlockRssi
        val lockThresh = cfg.sensitivityLockRssi

        val prev = gattEma
        // Sharp jump vs the current average = you're moving toward/away — sample fast next.
        val jumping = prev != null && abs(rssi - prev) >= JUMP_DB
        val alpha = if (nextIntervalMs <= MONITOR_FAST_MS || jumping) ALPHA_FAST else ALPHA_SLOW
        val next = prev?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        gattEma = next
        val smoothed = next.toInt()
        val prevZone = _state.value.zone

        val newZone = when {
            smoothed >= unlockThresh -> Zone.NEAR
            smoothed <= lockThresh -> Zone.FAR
            else -> prevZone // hysteresis band
        }
        _state.value = _state.value.copy(
            phase = Phase.MONITORING, source = Source.GATT,
            rawRssi = rssi, smoothedRssi = smoothed, zone = newZone, error = null,
        )

        // Choose the NEXT poll cadence: burst while near a threshold (a crossing is imminent)
        // or on a sharp move; slow while solidly inside a zone and steady.
        nextIntervalMs = when {
            jumping -> MONITOR_FAST_MS
            smoothed in (lockThresh - WATCH_MARGIN_DB)..(unlockThresh + WATCH_MARGIN_DB) -> MONITOR_FAST_MS
            else -> MONITOR_SLOW_MS
        }

        if (newZone != prevZone) {
            when (newZone) {
                // Only unlock on a real transition INTO near from a known farther zone — never
                // on the first reading (prevZone UNKNOWN), so we don't auto-unlock just because
                // the app/session came up while you were already standing at the car.
                Zone.NEAR -> if (prevZone != Zone.UNKNOWN) trigger("approach-unlock") { lock.unlock() }
                Zone.FAR -> if (prevZone == Zone.NEAR) trigger("walk-away-lock") { lock.lock() }
                else -> {}
            }
        }
    }

    // ---------------- action gate ----------------

    private fun trigger(label: String, action: suspend () -> Boolean) {
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

        /** Poll cadences. FAST = crossing imminent, SLOW = idle/low-power, MID = reconnecting. */
        private const val MONITOR_FAST_MS = 250L
        private const val MONITOR_MID_MS = 800L
        private const val MONITOR_SLOW_MS = 2_000L

        /** Burst when the smoothed RSSI is within this many dB of either threshold. */
        private const val WATCH_MARGIN_DB = 6
        /** A per-sample change this large (dB) means you're moving — sample fast. */
        private const val JUMP_DB = 4

        /** EMA weights: light (fast-tracking) during a burst, heavy (stable) when idle. */
        private const val ALPHA_FAST = 0.6
        private const val ALPHA_SLOW = 0.35

        /** How long the DK session must stay down (while last NEAR) before we lock on walk-away. */
        private const val LINK_LOSS_LOCK_DELAY_MS = 5_000L
    }
}
