package com.openzeekr.app.remote

import com.openzeekr.app.net.model.VehicleStatusBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live vehicle status. There is NO server push (see live-status-polling notes), so the
 * stock app polls: heartbeat + re-fetch. We mirror that with a foreground poll loop and
 * expose the latest [VehicleStatusBean] as a [StateFlow] every screen can observe — so
 * the UI updates on its own instead of a one-shot fetch. Started/stopped by [App] on
 * foreground/background.
 */
class VehicleStatusHolder(
    private val control: RemoteControlRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VehicleStatusBean?>(null)
    val state: StateFlow<VehicleStatusBean?> = _state.asStateFlow()

    @Volatile var lastError: String? = null; private set
    private var job: Job? = null
    private var burstJob: Job? = null

    /** Begin foreground polling (idempotent). control.status() heartbeats first. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; burstJob?.cancel(); burstJob = null }

    suspend fun refresh() {
        when (val r = control.status()) {
            is CallResult.Ok -> { _state.value = r.value; lastError = null }
            is CallResult.Err -> lastError = r.message
        }
    }

    /**
     * Fire a short burst of spaced [refresh]es AFTER a control command succeeds. The car applies
     * commands ASYNCHRONOUSLY — the cloud returns a sessionId immediately, but the vehicle status
     * Ts only advances a moment later — so a single immediate refresh reads stale data (the tile
     * would still show the pre-command state until the next 20 s routine poll). Polling at ~1.5 s /
     * 4 s / 8 s catches the change once the car reflects it. Deduped: a newer command cancels and
     * replaces any in-flight burst, so rapid in-modal tweaks (e.g. climate seat steps) collapse to
     * one burst instead of stacking. Reuses the existing [refresh]/poll path — no second poller.
     */
    fun refreshAfterCommand() {
        burstJob?.cancel()
        burstJob = scope.launch {
            for (gap in POST_CMD_DELAYS) {
                delay(gap)
                if (!isActive) return@launch
                refresh()
            }
        }
    }

    private companion object {
        /** RVS cadence — the stock app's slower status tier. */
        const val POLL_MS = 20_000L
        /** Inter-refresh gaps for the post-command burst (ms). Cumulative → refreshes land at
         *  ~1.5 s, ~4 s and ~8 s after the command, spanning the window the car typically needs to
         *  apply a cloud command and advance its status Ts. */
        val POST_CMD_DELAYS = longArrayOf(1_500L, 2_500L, 4_000L)
    }
}
