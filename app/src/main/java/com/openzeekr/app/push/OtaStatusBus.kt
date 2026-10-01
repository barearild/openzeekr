package com.openzeekr.app.push

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Live OTA assignment-status pushes (FCM `OTAAssignmentStatusUpdate`) fanned out to whatever screen is
 * watching. The push is the FRESHEST signal for the install lifecycle - it arrives the instant the car
 * changes state (CONSENT-GRANTED -> PENDING -> STARTED -> PROGRESS -> COMPLETED), several seconds ahead
 * of what `versionV2` polling returns. It carries the new state + reason + scheduled time, but NOT the
 * version/release-notes, so a watcher uses it to update the phase/percent immediately and re-fetch the
 * rest. Process-wide singleton: the FCM service (which has no UI) emits, the OTA screen collects.
 */
object OtaStatusBus {
    data class Event(
        val vin: String?,
        val status: String?,        // e.g. "INSTALLATION-PENDING" (hyphen form, as on the wire)
        val reason: String?,        // status code OR a numeric install percent during *-PROGRESS
        val scheduledTime: String?,
    )

    // No replay: a watcher reacts only to pushes that arrive while it is open (it fetches the current
    // state on open anyway), so a stale push can't briefly show an old phase. Buffer a few so a rapid
    // burst (GRANTED + PENDING within a second) isn't dropped, and tryEmit never blocks the FCM thread.
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    fun emit(e: Event) { _events.tryEmit(e) }
}
