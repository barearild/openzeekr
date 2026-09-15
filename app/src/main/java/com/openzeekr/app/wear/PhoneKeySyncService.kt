package com.openzeekr.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "OZWearKey"

/**
 * Phone side of the watch companion. Handles two Data-Layer conversations:
 *
 *  - **Key clone** ([WearKeyProtocol.PATH_REQUEST]): sends this phone's provisioned DK
 *    credential so the watch can lock/unlock the car itself. The Data Layer only connects
 *    the user's own paired devices (same signing key) and the channel is encrypted; the
 *    credential is a transient MessageClient message, never persisted as a DataItem.
 *
 *  - **BLE-link arbitration** (status / pause / resume): the car allows only ONE BLE peer, so
 *    before the watch touches the car it checks with the phone. On PAUSE the phone releases
 *    its keep-alive session and stands down ([WearLinkArbiter]) so the watch can connect
 *    cleanly; on RESUME it takes the link back. See [WearLinkArbiter].
 */
class PhoneKeySyncService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "phone got message path=${event.path} from ${event.sourceNodeId}")
        when (event.path) {
            WearKeyProtocol.PATH_REQUEST -> sendKey(event.sourceNodeId)
            WearKeyProtocol.PATH_STATUS_QUERY -> sendStatus(event.sourceNodeId)
            WearKeyProtocol.PATH_PAUSE -> pauseForWatch(event.sourceNodeId)
            WearKeyProtocol.PATH_RESUME -> {
                Log.i(TAG, "watch resumed — reclaiming car link")
                WearLinkArbiter.resume()
            }
        }
    }

    private fun sendKey(nodeId: String) {
        val blob = DkIdentity.get(this).exportCredentialBlob()
        if (blob == null) Log.w(TAG, "phone has no provisioned key to share")
        else Log.i(TAG, "phone sharing key (${blob.size} fields)")
        val json = if (blob != null) Json.encodeToString(blob) else "{}"
        send(nodeId, WearKeyProtocol.PATH_KEY, json.toByteArray(Charsets.UTF_8))
    }

    /** Tell the watch whether we're holding the car link and whether proximity is running. */
    private fun sendStatus(nodeId: String) {
        val deps = (application as? DepsHolder)?.deps
        val state = deps?.ble?.state?.value
        val connected = !WearLinkArbiter.linkSuspended.value &&
            (state == DkBleManager.State.CONNECTED || state == DkBleManager.State.SESSION_READY)
        val prox = deps?.config?.config?.value?.proximityEnabled == true
        val json = Json.encodeToString(mapOf("connected" to if (connected) "1" else "0", "prox" to if (prox) "1" else "0"))
        Log.i(TAG, "status → watch connected=$connected prox=$prox")
        send(nodeId, WearKeyProtocol.PATH_STATUS, json.toByteArray(Charsets.UTF_8))
    }

    /** Stand our keep-alive down, actively drop the session, let the car free the peer slot,
     *  then ack so the watch connects into a clean radio. */
    private fun pauseForWatch(nodeId: String) {
        val deps = (application as? DepsHolder)?.deps
        WearLinkArbiter.suspendForWatch()
        if (deps == null) { send(nodeId, WearKeyProtocol.PATH_PAUSED, ByteArray(0)); return }
        deps.appScope.launch(Dispatchers.Main) {
            runCatching { deps.ble.disconnect() }
            // Wait until we're actually disconnected (IDLE), THEN let the car free its single
            // peer slot before we tell the watch to connect — otherwise the watch races the
            // still-tearing-down link and its connect fails.
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    deps.ble.state.first { it == DkBleManager.State.IDLE || it == DkBleManager.State.ERROR }
                }
            }
            delay(CAR_SLOT_SETTLE_MS)
            Log.i(TAG, "link released for watch (state=${deps.ble.state.value}) — acking")
            send(nodeId, WearKeyProtocol.PATH_PAUSED, ByteArray(0))
        }
    }

    private fun send(nodeId: String, path: String, data: ByteArray) {
        Wearable.getMessageClient(this).sendMessage(nodeId, path, data)
            .addOnSuccessListener { Log.i(TAG, "sent $path (${data.size}B) to $nodeId") }
            .addOnFailureListener { e -> Log.w(TAG, "send $path failed", e) }
    }

    private companion object {
        const val CAR_SLOT_SETTLE_MS = 1000L
    }
}
