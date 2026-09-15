package com.openzeekr.app.wear

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phone-side BLE-link arbitration for the watch companion.
 *
 * The car accepts only ONE BLE peer at a time, so the phone and the watch must never hold
 * the DK session simultaneously. The phone's foreground keep-alive
 * ([com.openzeekr.app.ble.ProximityService]) is the sole owner of the connection; when the
 * watch needs the car (its "pause" message), the phone flips [linkSuspended] so the
 * keep-alive releases the link and stops reconnecting until the watch is done (its "resume"
 * message) — or until [AUTO_RESUME_MS] passes, in case the watch app dies mid-handover and
 * never sends resume (otherwise the phone would sit disconnected forever).
 */
object WearLinkArbiter {
    /** True while the watch is borrowing the car link; the keep-alive must stay off it. */
    val linkSuspended = MutableStateFlow(false)

    @Volatile private var suspendedAtMs = 0L

    fun suspendForWatch() {
        suspendedAtMs = System.currentTimeMillis()
        linkSuspended.value = true
    }

    fun resume() {
        suspendedAtMs = 0L
        linkSuspended.value = false
    }

    /** Fail-safe: has the watch held the link past the auto-resume deadline without resuming? */
    fun expired(nowMs: Long = System.currentTimeMillis()): Boolean =
        linkSuspended.value && suspendedAtMs != 0L && nowMs - suspendedAtMs >= AUTO_RESUME_MS

    /** If the watch never sends "resume" (app killed mid-handover), reclaim the link after this. */
    const val AUTO_RESUME_MS = 45_000L
}
