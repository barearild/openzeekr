package com.openzeekr.app.ble

/**
 * Digital-key lock / unlock over the BLE DK session.
 *
 * Requirement #1: placeholder only — the DK unlock opcode/payload build depends
 * on the not-yet-finished handshake, so these delegate to the session and will
 * throw NotYetReversed until a real [DkSession] is in place. The method surface
 * is final so the UI can bind to it now.
 */
class DkLockController(private val session: DkSession) {

    /** VehicleCtrlCmd.UNLOCK over DK (placeholder). */
    suspend fun unlock(): Boolean {
        ensureSession()
        // TODO(dk): opcode + payload for UNLOCK once reversed.
        return session.sendFrame(DkOpcodes.CMD_A2V_VEHICLE_CTRL, byteArrayOf(DkOpcodes.CTRL_UNLOCK))
    }

    /** VehicleCtrlCmd.LOCK over DK (placeholder). */
    suspend fun lock(): Boolean {
        ensureSession()
        return session.sendFrame(DkOpcodes.CMD_A2V_VEHICLE_CTRL, byteArrayOf(DkOpcodes.CTRL_LOCK))
    }

    private suspend fun ensureSession() {
        if (!session.isEstablished) session.establish()
    }
}

/** Opcodes shared by the DK channel (subset; extend as reversing completes). */
object DkOpcodes {
    // Vehicle control instruction envelope (placeholder id — confirm from DkCmd).
    const val CMD_A2V_VEHICLE_CTRL = 0x0100

    // RPA opcodes (confirmed from DkCmd.smali).
    const val CMD_A2V_RPA_REQ = 0x113
    const val CMD_V2A_RPA_CHALLENGE = 0x115
    const val CMD_A2V_RPA_ANSWER = 0x116
    const val CMD_A2V_RSSI_SYNC = 0x158

    // VehicleCtrlCmd values (placeholders; real byte encoding TBD).
    const val CTRL_LOCK: Byte = 0x01
    const val CTRL_UNLOCK: Byte = 0x02
}
