package com.openzeekr.app.ble

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * CAR-side walk-away AUTO-LOCK — a PARALLEL, redundant safety net, NOT an exclusive alternative.
 *
 * The phone-side [ProximityController] ALWAYS runs: it owns approach-UNLOCK and its own walk-away
 * lock. This controller runs *alongside* it (when [com.openzeekr.app.config.SecretsConfig.carSideAutoLock]
 * is on) and hands the LOCK job redundantly to the CAR's own firmware:
 *  - it uploads the per-phone-model RSSI CALIBRATION so the vehicle can range this phone, and
 *  - it observes the car's autonomous approach auto-lock (the car->phone 0x0159 APPROACHLOCK_NOTIFY).
 *
 * WHY (safety): locking is safety-critical. If the phone-side walk-away lock ever fails — BLE link
 * drop, the app is killed, or RSSI is flaky — the car is left open. The vehicle's own firmware
 * auto-lock is a second, independent line of defence that can still secure the car. Approach-UNLOCK
 * stays 100% phone-side; this only ADDS a lock backstop.
 *
 * How it works (see [RealDkSession] + the reversing notes, dk-carside-proximity):
 *  1. Small calibration (0x0172 → 0x0173) is uploaded during the handshake ([RealDkSession.establish]).
 *  2. When [carSideAutoLock] is on and a session is ready, we upload the BIG calibration
 *     coefficients (0x0171, fragmented, 100 ms spacing, GATT ch1) via [RealDkSession.uploadBigCalibration]
 *     if the cloud provisioned them ([DkCredential.coefBig], from key-info coefBigParam).
 *  3. The 0x0159 notify is surfaced as [autoLockEvents] (+ counters in [state]) so higher layers can
 *     confirm the car locked and optionally raise a notification (CarNotifier). Events are surfaced
 *     regardless of the toggle — a car auto-lock is always worth knowing about.
 *
 * The controller NEVER sends lock/unlock itself and never owns the BLE connection. It goes idle
 * (uploads nothing) when [carSideAutoLock] is off, so it is always safe to construct + start.
 *
 * HONEST LIMITATION: 0x0159 is auto-LOCK ONLY (no approach-UNLOCK notify) — which is exactly what a
 * lock safety-net wants. Whether the car actually ranges + acts on the calibration is a SOFT blocker
 * confirmable only at the car (car-side approach/walk-away feature enabled + correct BLE-RSSI coef).
 */
class CarProximityController(
    private val store: ConfigStore,
    private val ble: DkBleManager,
    private val scope: CoroutineScope,
) {
    enum class Phase {
        /** Car-side auto-lock toggle is off, or the controller is stopped. */
        IDLE,
        /** Toggle on, waiting for a live DK session. */
        WAITING_SESSION,
        /** Uploading the big calibration coefficients. */
        CALIBRATING,
        /** Calibration uploaded — the car should now be ranging + able to walk-away auto-lock. */
        ARMED,
        /** Big-calibration upload failed / skipped (e.g. coefBig not provisioned). */
        CALIB_FAILED,
    }

    /** A single car->phone approach auto-lock occurrence (0x0159), for [autoLockEvents]. */
    data class AutoLockEvent(val atMs: Long)

    data class State(
        val running: Boolean = false,
        val phase: Phase = Phase.IDLE,
        /** True once the big-calibration upload succeeded for the current session. */
        val calibrationUploaded: Boolean = false,
        /** Count + timestamp of observed 0x0159 approach auto-lock events (informational). */
        val autoLockCount: Int = 0,
        val lastAutoLockAtMs: Long = 0L,
        val note: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Discrete stream of car walk-away auto-lock events (0x0159). Higher layers collect this to raise
     * a notification (com.openzeekr.app.util CarNotifier) or reconcile UI lock state. `replay = 1` so a
     * late collector still sees the most recent event; extra buffer + DROP_OLDEST so a slow collector
     * never blocks the BLE inbound path.
     */
    private val _autoLockEvents = MutableSharedFlow<AutoLockEvent>(
        replay = 1, extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val autoLockEvents: SharedFlow<AutoLockEvent> = _autoLockEvents.asSharedFlow()

    private var superviseJob: Job? = null
    private var unregisterApproach: (() -> Unit)? = null

    /** Per-session guard so we upload the big calibration exactly once per established session. */
    @Volatile private var calibratedForSession = false

    /**
     * Walk-away-lock command bookkeeping. We (re)ARM the car-side setting (ENABLE) once per session
     * while the toggle is on — a safety feature should be re-armed on every reconnect — but we only
     * ever send DISABLE in response to an explicit user turn-OFF ([pendingDisable]), never as an idle
     * default. That asymmetry is deliberate: silently pushing DISABLE on connect would clobber a
     * walk-away-lock the owner enabled in the car's own HMI. [lastToggle] tracks the config value so a
     * change is recognised as a real user flip; [enableArmedThisSession] guards the once-per-session ARM.
     */
    @Volatile private var lastToggle: Boolean = false
    @Volatile private var pendingDisable: Boolean = false
    @Volatile private var enableArmedThisSession: Boolean = false

    // ---------------- lifecycle ----------------

    fun start() {
        if (_state.value.running) return
        lastToggle = store.current().carSideAutoLock   // seed WITHOUT treating startup as a user flip
        _state.value = State(running = true, phase = Phase.IDLE, note = "started")
        Logx.d("carprox", "CarProximityController started (parallel car-side walk-away auto-lock net)")

        // Observe the 0x0159 approach auto-lock event for the whole lifetime (additive listener that
        // does not disturb RpaController's inbound handler). Surfaced regardless of the toggle.
        unregisterApproach = (ble.session as? RealDkSession)?.onApproachLock { onApproachAutoLock() }

        // React to (toggle, ble-state) changes:
        //  - a user turn-OFF is queued ([pendingDisable]) and pushed to the car once, even across a reconnect;
        //  - while ON + session ready we ARM the car (ENABLE) once per session and upload big calibration;
        //  - we never send DISABLE just because the toggle sits off (don't clobber the car's own setting).
        superviseJob = scope.launch {
            combine(store.config, ble.state) { cfg, bleState -> cfg.carSideAutoLock to bleState }
                .distinctUntilChanged()
                .collect { (enabled, bleState) ->
                    if (enabled != lastToggle) {                 // real user flip
                        lastToggle = enabled
                        if (!enabled) pendingDisable = true      // remember to tell the car we turned it off
                    }
                    if (bleState != DkBleManager.State.SESSION_READY) {
                        calibratedForSession = false
                        enableArmedThisSession = false           // re-arm on the next connect
                        setState { it.copy(
                            phase = if (enabled) Phase.WAITING_SESSION else Phase.IDLE,
                            calibrationUploaded = false,
                            note = if (enabled) "waiting for DK session ($bleState)" else "car-side auto-lock off — idle",
                        ) }
                        return@collect
                    }
                    // Session READY.
                    if (enabled) {
                        pendingDisable = false                   // superseded by an ON state
                        if (!enableArmedThisSession && applyWalkAwayLock(true)) enableArmedThisSession = true
                        if (!calibratedForSession) runCalibration()
                    } else {
                        if (pendingDisable && applyWalkAwayLock(false)) pendingDisable = false
                        calibratedForSession = false
                        enableArmedThisSession = false
                        setState { it.copy(phase = Phase.IDLE, calibrationUploaded = false, note = "car-side auto-lock off — idle") }
                    }
                }
        }
    }

    fun stop() {
        superviseJob?.cancel(); superviseJob = null
        unregisterApproach?.invoke(); unregisterApproach = null
        calibratedForSession = false
        enableArmedThisSession = false
        _state.value = State(running = false, phase = Phase.IDLE, note = "stopped")
        Logx.d("carprox", "CarProximityController stopped")
    }

    // ---------------- walk-away-lock enable/disable ----------------

    /**
     * Arming the car-side walk-away-lock comfort setting. NO-OP over BLE by design: the disassembly +
     * the stock DK BLE trace (2026-09-19) show the stock app does NOT send the 0x0151 CUST_REQ
     * TYPE_WALK_AWAY_LOCK over BLE at all - the switch is toggled via CLOUD/TSP
     * (CustomControlType.CONTROL_TYPE_WALK_AWAY_LOCK) or the car's own menu, which the owner enables
     * once. So sending 0x0151 here was redundant (the constant is defined but never sent by stock);
     * we drop it to match stock exactly. We keep this hook (returns true) so the calibration + arming
     * bookkeeping still run, and so a future CLOUD enable can slot in here. Unlock stays 100%
     * phone-side ([ProximityController]).
     * @return always true (nothing to send over BLE).
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun applyWalkAwayLock(enable: Boolean): Boolean = true

    // ---------------- calibration ----------------

    private suspend fun runCalibration() {
        val session = ble.session as? RealDkSession ?: run {
            setState { it.copy(phase = Phase.CALIB_FAILED, note = "no real DK session") }
            return
        }
        setState { it.copy(phase = Phase.CALIBRATING, note = "uploading big calibration (0x0171)") }
        Logx.d("carprox", "session ready + car-side auto-lock on — uploading big calibration")
        val ok = runCatching { session.uploadBigCalibration() }
            .onFailure { Logx.w("carprox", "big calibration upload error: ${it.message}") }
            .getOrDefault(false)
        if (ok) {
            calibratedForSession = true
            setState { it.copy(phase = Phase.ARMED, calibrationUploaded = true, note = "calibration uploaded — car ranging") }
            Logx.d("carprox", "big calibration uploaded — car should now range this phone for walk-away lock")
        } else {
            // Don't set calibratedForSession: allow a retry on the next SESSION_READY transition.
            // NOTE: the small calibration (0x0172) is ALWAYS sent at handshake, so the car may still be
            // able to range with small-only even when the big upload is skipped (coefBig unprovisioned).
            setState { it.copy(phase = Phase.CALIB_FAILED, calibrationUploaded = false,
                note = "big-calib skipped (coefBig not provisioned) — small-calib from handshake still applies") }
            Logx.w("carprox", "big calibration NOT uploaded (coefBig missing or write failed); small-calib still sent at handshake")
        }
    }

    // ---------------- inbound event ----------------

    /** Invoked from [RealDkSession] when a 0x0159 APPROACHLOCK_NOTIFY arrives (car auto-locked). */
    private fun onApproachAutoLock() {
        val now = System.currentTimeMillis()
        setState { it.copy(autoLockCount = it.autoLockCount + 1, lastAutoLockAtMs = now,
            note = "car reported walk-away auto-LOCK") }
        _autoLockEvents.tryEmit(AutoLockEvent(now))
        Logx.d("carprox", "observed car walk-away auto-LOCK (0x0159)")
    }

    private inline fun setState(transform: (State) -> State) { _state.value = transform(_state.value) }
}
