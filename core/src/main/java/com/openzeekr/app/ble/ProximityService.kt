package com.openzeekr.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openzeekr.app.Deps
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.util.Logx
import com.openzeekr.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the digital key live once the phone is provisioned:
 *
 *  1. **Keep-alive** — holds the DK BLE session connected to the car (reconnecting
 *     whenever it drops) so manual and approach lock/unlock are instant.
 *  2. **Approach** — when the "lock/unlock on approach" setting is on, runs the
 *     RSSI proximity controller (approach-unlock / walk-away-lock).
 *
 * Started by the app once logged in + provisioned (see AppBootstrap); pair with a
 * battery-optimization exemption for reliability. Runs as a connectedDevice FGS.
 */
class ProximityService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loops: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val deps = (application as? DepsHolder)?.deps ?: return START_STICKY
        if (loops?.isActive != true) {
            loops = scope.launch {
                launch { keepConnected(deps) }
                launch { runApproach(deps) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loops?.cancel(); loops = null
        (application as? DepsHolder)?.deps?.proximity?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Hold the DK BLE session connected; reconnect whenever it goes idle/errored.
     *
     * This is the SOLE owner of the connection — it runs regardless of the proximity
     * setting, so the session stays up constantly (instant lock/unlock, a stable link for
     * RPA, and no churn). The [ProximityController] only reads RSSI off this live session;
     * it never connects or disconnects, so the two can't fight over the GATT.
     */
    private suspend fun keepConnected(deps: Deps) {
        while (scope.isActive) {
            // The watch is borrowing the car link (only one BLE peer allowed): stand down —
            // release our session and don't reconnect until it resumes us (or the fail-safe
            // deadline passes, in case the watch app died mid-handover).
            if (com.openzeekr.app.wear.WearLinkArbiter.linkSuspended.value) {
                if (com.openzeekr.app.wear.WearLinkArbiter.expired()) {
                    Logx.d("svc", "keep-alive: watch link-borrow expired — reclaiming")
                    com.openzeekr.app.wear.WearLinkArbiter.resume()
                } else {
                    when (deps.ble.state.value) {
                        DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {}
                        else -> { Logx.d("svc", "keep-alive: releasing link for watch"); runCatching { deps.ble.disconnect() } }
                    }
                    delay(WATCH_YIELD_POLL_MS)
                    continue
                }
            }
            if (deps.ble.hasCredential && deps.ble.bluetoothAvailable) {
                when (deps.ble.state.value) {
                    DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                        Logx.d("svc", "keep-alive: (re)connecting DK session")
                        runCatching { deps.ble.connect(null) }
                    }
                    else -> {}
                }
            }
            delay(RECONNECT_INTERVAL_MS)
        }
    }

    /** Start/stop the RSSI approach controller to follow the persisted setting. */
    private suspend fun runApproach(deps: Deps) {
        deps.config.config.collect { cfg ->
            val running = deps.proximity.state.value.running
            if (cfg.proximityEnabled && !running) runCatching { deps.proximity.start() }
            else if (!cfg.proximityEnabled && running) runCatching { deps.proximity.stop() }
        }
    }

    private fun startInForeground() {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenZeekr digital key active")
            .setContentText("Keeping your key connected for lock/unlock")
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Digital key", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "proximity"
        private const val NOTIF_ID = 42
        private const val RECONNECT_INTERVAL_MS = 8_000L
        /** While yielded to the watch, poll faster so we notice resume/expiry promptly. */
        private const val WATCH_YIELD_POLL_MS = 1_000L

        fun start(context: Context) {
            val i = Intent(context, ProximityService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }
    }
}
