package com.openzeekr.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.launch

/**
 * Transparent, headless activity triggered by tapping an NFC tag (e.g. Samsung TecTile).
 *
 * Dispatches the requested door action (toggle / unlock / lock) over the live BLE digital key
 * session (instant), falling back to the cloud TSP command if BLE is disconnected.
 */
class NfcActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = (application as App).deps
        val uri = intent.data
        val host = uri?.host?.lowercase() ?: "toggle"
        val action = when (host) {
            "lock" -> Action.LOCK
            "unlock" -> Action.UNLOCK
            else -> Action.TOGGLE
        }

        deps.appScope.launch {
            try {
                val lockIt = when (action) {
                    Action.LOCK -> true
                    Action.UNLOCK -> false
                    Action.TOGGLE -> {
                        val status = deps.vehicleState.state.value?.additionalVehicleStatus
                            ?.drivingSafetyStatus?.centralLockingStatus
                        // If car is unlocked ("0"), lock it. If locked ("1") or unknown, unlock it.
                        status == "0"
                    }
                }

                Logx.d("nfc", "NFC tap triggered action=$action (lockIt=$lockIt)")
                val label = if (lockIt) "Lock" else "Unlock"
                vibrate(lockIt)

                // Reset proximity latch if locking
                if (lockIt) deps.proximity.resetArmedUnlocked("nfc-lock")

                val success = if (deps.ble.state.value == DkBleManager.State.SESSION_READY) {
                    val ok = if (lockIt) deps.lock.lock() else deps.lock.unlock()
                    Logx.d("nfc", "NFC $label via BLE key -> $ok")
                    ok
                } else {
                    Logx.d("nfc", "BLE not ready (${deps.ble.state.value}), sending $label via cloud")
                    val cmd = if (lockIt) Command.LOCK else Command.UNLOCK
                    deps.control.send(cmd) is CallResult.Ok
                }

                toast(if (success) "Zeekr $label ✓" else "$label failed ✗")
            } catch (t: Throwable) {
                Logx.e("nfc", "Failed to handle NFC tap: ${t.message}", t)
                toast("NFC action failed: ${t.message}")
            } finally {
                finish()
            }
        }
    }

    private fun vibrate(lockIt: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = if (lockIt) longArrayOf(0, 100) else longArrayOf(0, 60, 40, 80)
                val amplitudes = if (lockIt) intArrayOf(0, 255) else intArrayOf(0, 200, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (lockIt) 100L else 180L)
            }
        } catch (t: Throwable) {
            Logx.w("nfc", "Vibrate failed: ${t.message}")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private enum class Action { LOCK, UNLOCK, TOGGLE }
}
