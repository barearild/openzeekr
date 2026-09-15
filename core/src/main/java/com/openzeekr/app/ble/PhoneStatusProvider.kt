package com.openzeekr.app.ble

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import com.openzeekr.app.AppForeground
import com.openzeekr.app.ble.rpa.PhoneStatus

/**
 * Produces the live phone-status byte packed into every RPA frame.
 *
 * Byte layout (stock `cb/a.d(III)`, RPA_SEQUENCE_FINDINGS): a SINGLE packed byte —
 *   bits 7..6 = phone-state (call / background / normal): `(state & 3) << 6`
 *   bits 5..1 = battery% / 5 (0..20, fits 0..31):         `((batt/5) & 0x1f) << 1`
 *   bit  0    = signal present:                            `signal & 1`
 *
 * We previously sent just the 0..3 state value as the WHOLE byte — i.e. battery=0,
 * signal=0 — which reads as "dead phone, no signal", a state a remote-parking car may
 * legitimately refuse (it won't hand a dead-man's-switch maneuver to a phone that reports
 * it is about to die). A healthy idle phone is ~0x21, not 0x00.
 *
 * Call-state is read via AudioManager.mode (no READ_PHONE_STATE needed); foreground via
 * [AppForeground]; battery via BatteryManager.
 */
class PhoneStatusProvider(context: Context) {
    private val appCtx = context.applicationContext
    private val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val battery = appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    fun stateByte(): Byte {
        val inCall = audio?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION } == true
        val state = when {
            inCall -> PhoneStatus.CALL
            !AppForeground.isForeground -> PhoneStatus.BACKGROUND
            else -> PhoneStatus.NORMAL
        }
        // Battery percent (0..100); default to full if unavailable, and never let the
        // field be 0 — a 0 battery field is exactly the "dead phone" reading we must avoid.
        val pct = (battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100)
            .let { if (it in 1..100) it else 100 }
        val battField = (pct / 5).coerceIn(1, 0x1f)
        val signal = 1 // assume connected while the app is driving RPA
        val packed = ((state.code and 0x3) shl 6) or ((battField and 0x1f) shl 1) or (signal and 0x1)
        return packed.toByte()
    }
}
