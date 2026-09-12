package com.openzeekr.app.ble.rpa

/**
 * Remote Parking Assist protocol constants, taken verbatim from the app's
 * `RPAReqConstants` / `RPAConstants` (see dk-rpa-remote-parking notes). These
 * are the real on-wire values; only the crypto wrapping (session) is placeholder.
 */
object RpaReq {
    // control bytes placed in the RPA request payload
    const val CMD_NONE: Byte = 0x0
    const val CMD_RPA_REQ_MODE: Byte = 0x1
    const val CMD_RPA_STOP: Byte = 0x2
    const val CMD_RPA_OUT_MODE_SET: Byte = 0x3
    const val CMD_RPA_UNDO: Byte = 0x4
    const val CMD_RPA_START_SEARCHING_SLOT: Byte = 0x5
    const val CMD_RPA_START_PARKING_IN: Byte = 0x6
    const val CMD_RPA_START_PARKING_OUT: Byte = 0x7
    const val CMD_RPA_PAUSE: Byte = 0x8
    const val CMD_RPA_CONTINUE: Byte = 0x9
    const val CMD_RPA_PARK_OUT_REQUEST: Byte = 0xb
    const val CMD_RPA_PARKIN_REQUEST: Byte = 0xd
    const val CMD_RPA_PRKG_ON: Byte = 0xe
    const val CMD_RPA_PRKG_OFF: Byte = 0xf.toByte()

    // RSPA (remote straight — the hold-to-move joystick)
    const val CMD_RSPA_REQUEST: Byte = 0x1
    const val CMD_RSPA_BACKWARD: Byte = 0x2
    const val CMD_RSPA_FORWARD: Byte = 0x3
    const val CMD_RSPA_BOTTOM_RELEASE: Byte = 0x4
    const val CMD_RSPA_QUIT: Byte = 0x5
    const val CMD_RSPA_COMPLETE: Byte = 0x6

    // park-out directions (CMD_RPA_OUT_MODE_SET operand)
    const val PARALLEL_RIGHT_OUT: Byte = 0x1
    const val PARALLEL_LEFT_OUT: Byte = 0x2
    const val HEAD_PERPENDICULAR_RIGHT_OUT: Byte = 0x3
    const val HEAD_PERPENDICULAR_LEFT_OUT: Byte = 0x4
    const val TAIL_PERPENDICULAR_RIGHT_OUT: Byte = 0x5
    const val TAIL_PERPENDICULAR_LEFT_OUT: Byte = 0x6
}

object RpaConst {
    const val MODE_RPA = 1
    const val MODE_RSPA = 2
    const val MODE_RPA_PLUS = 3
    const val MODE_RPA_OUT = 4

    // status bytes (subset)
    const val RPA_PREPARED: Byte = 0x1
    const val RPA_CANCEL: Byte = 0x3
    const val RPA_QUIT: Byte = 0x4
    const val RPA_OFF: Byte = 0x5
    const val RPA_COMPLETED: Byte = 0x6
    const val RPA_OUT_OF_DISTANCE: Byte = -0x16
    const val RPA_PARKING_IN: Byte = -0x18
    const val RPA_PARKING_OUT: Byte = -0x17
    const val RPA_SUSPEND: Byte = -0x20

    // BLE lifecycle events
    const val EVENT_BLUE_UNAUTH = 0x3e8
    const val EVENT_BLUE_DISCONNECTED = 0x3ee
    const val EVENT_INIT_DK_SUCCESS = 0x7d0

    /** Heartbeat cadence while a hold-to-move maneuver is armed (ms). */
    const val HEARTBEAT_MS = 500L
}

/** Phone health byte packed into each RPA frame (signal/battery/call-state). */
enum class PhoneStatus(val code: Int) { NORMAL(0), BACKGROUND(1), CALL(2), FOREGROUND(3) }
