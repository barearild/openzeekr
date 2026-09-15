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

    // Held ONLY while engaged (connecting / connected / session live). A FGS keeps the PROCESS alive
    // but does NOT keep the CPU awake, and screen-off BLE work (RSSI polling, GATT callbacks) needs
    // the CPU up — so we hold this during a live session. When idle we DON'T hold it: the
    // hardware-offloaded presence scan (DkBleManager.armPresenceScan) watches for the car with the
    // CPU asleep and wakes us via BleScanReceiver. That removes the always-on wakelock that drained
    // the battery while parked at home. (Legacy path, presenceOffloadEnabled=false: held whenever we
    // want a connection, i.e. continuously.)
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // One-shot probe for the "app started right next to the car" case: FIRST_MATCH is edge-triggered
    // and may not fire for a car already in range when the offload scan is armed, so we do a single
    // foreground connect attempt the first time we go idle-with-offload.
    private var didInitialProbe = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val deps = (application as? DepsHolder)?.deps ?: return START_STICKY

        // Presence signals delivered by BleScanReceiver (offloaded scan woke us).
        when (intent?.action) {
            ACTION_PRESENT -> {
                val mac = intent.getStringExtra(EXTRA_MAC)
                // NOTE: do NOT connect(mac) directly. The car advertises a Resolvable Private
                // Address, so the MAC from the offloaded result is a RANDOM address; a direct
                // getRemoteDevice(mac).connectGatt treats it as PUBLIC and times out (status=147).
                // The offloaded scan is only a WAKE trigger — re-run the proven scan-based connect,
                // which takes the BluetoothDevice from the live ScanResult (correct address type).
                Logx.d("svc", "presence: car in range (saw $mac) — engaging via scan-connect")
                deps.ble.disarmPresenceScan()
                acquireWakeLock()
                runCatching { deps.ble.connect(null) }
            }
            ACTION_ABSENT -> {
                // MATCH_LOST. If we're not in a live session there's nothing to hold power for;
                // the loop keeps the offload armed. A live session's walk-away lock is driven by the
                // connected-RSSI controller / link-loss, not this coarse signal.
                if (deps.ble.state.value == DkBleManager.State.IDLE ||
                    deps.ble.state.value == DkBleManager.State.ERROR) {
                    Logx.d("svc", "presence: car out of range and no session — releasing wakelock")
                    releaseWakeLock()
                }
            }
        }

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
        (application as? DepsHolder)?.deps?.let {
            it.proximity.stop()
            runCatching { it.ble.disarmPresenceScan() }
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }  // no timeout: released explicitly in onDestroy
        }
        Logx.d("svc", "wakelock acquired (CPU stays awake for screen-off keep-alive/proximity)")
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
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
                val offload = deps.config.config.value.presenceOffloadEnabled
                when (deps.ble.state.value) {
                    DkBleManager.State.IDLE, DkBleManager.State.ERROR -> {
                        if (offload) {
                            // Zero-CPU idle: let the controller watch for the car and wake us via
                            // BleScanReceiver. Release the wakelock so a parked phone can sleep.
                            if (!didInitialProbe) {
                                // First idle tick: the car may already be in range (app launched next
                                // to it), where FIRST_MATCH won't fire — do one foreground probe.
                                didInitialProbe = true
                                Logx.d("svc", "keep-alive: initial presence probe (already-at-car case)")
                                acquireWakeLock()
                                runCatching { deps.ble.connect(null) }
                            } else {
                                deps.ble.armPresenceScan()
                                releaseWakeLock()
                            }
                        } else {
                            // Legacy: keep a session up continuously (instant lock/unlock, stable RPA
                            // link) at the cost of a permanently-held wakelock + foreground scan.
                            acquireWakeLock()
                            Logx.d("svc", "keep-alive: (re)connecting DK session")
                            runCatching { deps.ble.connect(null) }
                        }
                    }
                    // Engaged (scanning/connecting/connected/session): CPU must stay up; the offload
                    // scan is redundant while we hold a link, so drop it.
                    else -> {
                        acquireWakeLock()
                        if (deps.ble.presenceArmed) deps.ble.disarmPresenceScan()
                        // Re-probe on the next return to idle only if we actually had a session that
                        // then dropped — a genuine walk-away leaves the car gone, so stay armed; but
                        // reset the probe flag so a later fresh start still checks the at-car case.
                    }
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
        private const val WAKELOCK_TAG = "openzeekr:dk-keepalive"
        private const val CHANNEL_ID = "proximity"
        private const val NOTIF_ID = 42
        private const val RECONNECT_INTERVAL_MS = 8_000L
        /** While yielded to the watch, poll faster so we notice resume/expiry promptly. */
        private const val WATCH_YIELD_POLL_MS = 1_000L

        /** BleScanReceiver → service: the car's advert just entered range (FIRST_MATCH). */
        const val ACTION_PRESENT = "com.openzeekr.app.ble.PROX_PRESENT"
        /** BleScanReceiver → service: the car's advert left range (MATCH_LOST) or offload dropped. */
        const val ACTION_ABSENT = "com.openzeekr.app.ble.PROX_ABSENT"
        const val EXTRA_MAC = "mac"

        fun start(context: Context) {
            val i = Intent(context, ProximityService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }

        /** Woken by the offloaded scan: car is nearby — engage (connect + approach). */
        fun notifyPresent(context: Context, mac: String?) {
            val i = Intent(context, ProximityService::class.java)
                .setAction(ACTION_PRESENT)
                .putExtra(EXTRA_MAC, mac)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        /** Woken by the offloaded scan: car left range (or the offload was dropped). */
        fun notifyPresenceLost(context: Context) {
            val i = Intent(context, ProximityService::class.java).setAction(ACTION_ABSENT)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}
