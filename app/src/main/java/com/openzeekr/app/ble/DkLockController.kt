package com.openzeekr.app.ble

import com.openzeekr.app.util.Logx

/**
 * Digital-key lock / unlock over the BLE DK session.
 *
 * Backed by [RealDkSession]: [ensureSession] runs the DK handshake if needed,
 * then each call sends a `CMD_A2V_CONTROL` (0x0110) frame carrying the
 * VehicleCtrlCmd byte (UNLOCK=0x01 / LOCK=0x02), GCM-encrypted by the session.
 * Requires a provisioned credential (DkBleManager.setCredential).
 */
class DkLockController(private val session: DkSession) {

    /** VehicleCtrlCmd.UNLOCK over DK. */
    suspend fun unlock(): Boolean {
        Logx.d("lock", "UNLOCK requested")
        ensureSession()
        return session.sendFrame(DkOpcodes.CMD_A2V_VEHICLE_CTRL, byteArrayOf(DkOpcodes.CTRL_UNLOCK))
            .also { Logx.d("lock", "UNLOCK sent ok=$it") }
    }

    /** VehicleCtrlCmd.LOCK over DK. */
    suspend fun lock(): Boolean {
        Logx.d("lock", "LOCK requested")
        ensureSession()
        return session.sendFrame(DkOpcodes.CMD_A2V_VEHICLE_CTRL, byteArrayOf(DkOpcodes.CTRL_LOCK))
            .also { Logx.d("lock", "LOCK sent ok=$it") }
    }

    private suspend fun ensureSession() {
        if (!session.isEstablished) session.establish()
    }
}

/**
 * DK channel opcodes — now sourced from [DkProtocol] (reversed + capture-verified).
 * NOTE: the control cmdId is 0x0110 (not 0x0100), and UNLOCK=0x01 / LOCK=0x02
 * (the earlier placeholders had these swapped).
 */
object DkOpcodes {
    const val CMD_A2V_VEHICLE_CTRL = DkProtocol.CMD_A2V_CONTROL   // 0x0110

    const val CMD_A2V_RPA_REQ = DkProtocol.CMD_A2V_RPA_REQ
    const val CMD_V2A_RPA_CHALLENGE = DkProtocol.CMD_V2A_RPA_CHALLENGE
    const val CMD_A2V_RPA_ANSWER = DkProtocol.CMD_A2V_RPA_ANSWER
    const val CMD_A2V_RSSI_SYNC = DkProtocol.CMD_A2V_RSSI_SYNC

    const val CTRL_UNLOCK: Byte = DkProtocol.CTRL_UNLOCK   // 0x01
    const val CTRL_LOCK: Byte = DkProtocol.CTRL_LOCK       // 0x02
}
