package com.openzeekr.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.openzeekr.app.util.Logx

/**
 * Delivers Google Play Services Activity Recognition transitions (our own PendingIntent) into
 * [MotionMonitor], the still/moving fallback when the phone has no hardware motion sensor. We
 * registered ENTER transitions only, so the latest event is the current activity.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            Logx.d("motion", "AR transition: activity=${event.activityType} trans=${event.transitionType}")
            MotionMonitor.deliver(event.activityType)
        }
    }

    companion object {
        const val ACTION = "com.openzeekr.app.ACTIVITY_TRANSITION"
    }
}
