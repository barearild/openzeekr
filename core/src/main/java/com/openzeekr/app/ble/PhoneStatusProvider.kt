package com.openzeekr.app.ble

import android.content.Context
import android.media.AudioManager
import com.openzeekr.app.AppForeground
import com.openzeekr.app.ble.rpa.PhoneStatus

/**
 * Produces the live phone-status byte packed into every RPA frame.
 *
 * The car uses this to abort a maneuver if the phone is unfit to drive it
 * (on a call, or the app no longer in the foreground). Call-state is read via
 * AudioManager.mode so it needs no READ_PHONE_STATE permission; foreground is
 * tracked by [AppForeground].
 *
 * NOTE: the stock app's byte also packs coarse signal-strength and battery
 * (cb/a.d(III)B); those are telemetry, not gates. We send the state value the
 * car actually reacts to; extend here if the exact packing is confirmed.
 */
class PhoneStatusProvider(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun stateByte(): Byte {
        val inCall = audio?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
        val status = when {
            inCall == true -> PhoneStatus.CALL
            !AppForeground.isForeground -> PhoneStatus.BACKGROUND
            else -> PhoneStatus.NORMAL
        }
        return status.code.toByte()
    }
}
