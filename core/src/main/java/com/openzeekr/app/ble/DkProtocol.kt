package com.openzeekr.app.ble

/**
 * Authoritative Zeekr DK BLE protocol constants.
 *
 * Reverse-engineered from the stock app (smali) and verified against live BLE
 * captures. Frame layout:
 *
 *   FE 03 | len(2 BE) | cmdId(2 BE) | instType(1) | body | CRC16/ARC(2 BE)
 *
 * where `len` is the TOTAL frame length (magic..CRC inclusive), headerLen = 7,
 * and body = plaintext for handshake frames or AES-128-GCM ciphertext for
 * control frames. See dk_ble_impl_spec.md.
 */
object DkProtocol {

    const val MAGIC: Byte = 0xFE.toByte()
    const val VER_ZEEKR: Byte = 0x03
    const val HEADER_LEN = 7          // magic(1)+ver(1)+len(2)+cmdId(2)+instType(1)
    const val CRC_LEN = 2

    // ---- InstType ----
    const val INST_INVALID = 0
    const val INST_REQ = 1
    const val INST_RSP = 2
    const val INST_CON = 3
    const val INST_ACK = 4
    const val INST_NON = 5
    const val INST_CMD = 6

    // ---- cmdId (2 BE on wire) ----
    const val CMD_A2V_CONNECT_CONFIRM = 0x0101
    const val CMD_V2A_DK_STATUS = 0x0102          // reply to 0x0101 (AES-CBC under connectKey)
    const val CMD_A2V_SEND_APP_CERT = 0x0103
    const val CMD_V2A_SEND_VEHICLE_CERT = 0x0104
    const val CMD_V2A_APP_CERT_VERIFY_FAILED = 0x0105
    const val CMD_A2V_SEND_APP_FACTOR = 0x0106
    const val CMD_V2A_PARSE_APP_FACTOR_FAILED = 0x0107
    const val CMD_V2A_SEND_VEHICLE_FACTOR = 0x0108
    const val CMD_A2V_DK_PRE_SYNC = 0x0109
    const val CMD_V2A_DK_PRE_SYNC_RESP = 0x010A
    const val CMD_A2V_SEND_DKEY = 0x010B
    const val CMD_V2A_DK_VERIFY_STATUS = 0x010C
    const val CMD_A2V_CONTROL = 0x0110
    const val CMD_V2A_CMD_RECEIVED = 0x0111
    const val CMD_V2A_RESULT = 0x0112
    const val CMD_A2V_RPA_REQ = 0x0113
    const val CMD_V2A_RPA_STATUS = 0x0114
    const val CMD_V2A_RPA_CHALLENGE = 0x0115
    const val CMD_A2V_RPA_ANSWER = 0x0116
    const val CMD_V2A_RPA_SYNC = 0x0117
    const val CMD_V2A_RPA_SYNC2 = 0x0118
    const val CMD_A2V_TRANS = 0x0120
    const val CMD_V2A_VSTATUS_SYNC = 0x0121
    const val CMD_A2V_PAIRING_REQ = 0x0137
    const val CMD_V2A_PAIRING_RESP = 0x0138
    const val CMD_A2V_CUST_REQ = 0x0151
    const val CMD_V2A_CUST_RESP = 0x0152
    const val CMD_A2V_RSSI_SYNC = 0x0158
    const val CMD_V2A_APPROACHLOCK_NOTIFY = 0x0159
    const val CMD_A2V_BIG_CALIBRATION_DATA = 0x0171
    const val CMD_A2V_SMALL_CALIBRATION_DATA = 0x0172
    const val CMD_V2A_SMALL_CALIBRATION_DATA_RESP = 0x0173
    const val CMD_INVALID = 0xFFFF
    /** Plaintext transport ACK the phone sends for a car->phone push (stock acks each 0x0121
     *  VSTATUS_SYNC): body = nSeq(2) ts(4) ackedCmdId(2) status(2=0x1000), instType=INST_ACK(4),
     *  on ch1-write, NOT GCM. Confirmed in the stock DK BLE trace 2026-09-19. */
    const val CMD_A2V_ACK = 0xFFFE

    /** Short human name for a frame opcode, for readable BLE logs. Unmapped opcodes (e.g. the car's
     *  0x182 periodic push, which lives in the native DK lib and isn't modelled here) read as
     *  "unknown" so they stand out in the trace. */
    fun name(cmdId: Int): String = when (cmdId) {
        CMD_A2V_CONNECT_CONFIRM -> "CONNECT_CONFIRM"
        CMD_V2A_DK_STATUS -> "DK_STATUS"
        CMD_A2V_DK_PRE_SYNC -> "DK_PRE_SYNC"; CMD_V2A_DK_PRE_SYNC_RESP -> "DK_PRE_SYNC_RESP"
        CMD_A2V_SEND_DKEY -> "SEND_DKEY"; CMD_V2A_DK_VERIFY_STATUS -> "DK_VERIFY_STATUS"
        CMD_A2V_CONTROL -> "CONTROL"; CMD_V2A_CMD_RECEIVED -> "CMD_RECEIVED"; CMD_V2A_RESULT -> "RESULT"
        CMD_A2V_RPA_REQ -> "RPA_REQ"; CMD_V2A_RPA_STATUS -> "RPA_STATUS"; CMD_V2A_RPA_CHALLENGE -> "RPA_CHALLENGE"
        CMD_A2V_RPA_ANSWER -> "RPA_ANSWER"; CMD_V2A_RPA_SYNC -> "RPA_SYNC"; CMD_V2A_RPA_SYNC2 -> "RPA_SYNC2"
        CMD_A2V_TRANS -> "TRANS"; CMD_V2A_VSTATUS_SYNC -> "VSTATUS_SYNC"
        CMD_A2V_PAIRING_REQ -> "PAIRING_REQ"; CMD_V2A_PAIRING_RESP -> "PAIRING_RESP"
        CMD_A2V_CUST_REQ -> "CUST_REQ"; CMD_V2A_CUST_RESP -> "CUST_RESP"
        CMD_A2V_RSSI_SYNC -> "RSSI_SYNC"; CMD_V2A_APPROACHLOCK_NOTIFY -> "APPROACHLOCK_NOTIFY"
        CMD_A2V_BIG_CALIBRATION_DATA -> "BIG_CALIB"; CMD_A2V_SMALL_CALIBRATION_DATA -> "SMALL_CALIB"
        CMD_V2A_SMALL_CALIBRATION_DATA_RESP -> "SMALL_CALIB_RESP"
        CMD_A2V_ACK -> "ACK"
        else -> "unknown"
    }

    // ---- VehicleCtrlCmd.cmdType (the mControlType byte); cmdType = enum ordinal - 1 ----
    const val CTRL_NULL: Byte = 0x00
    const val CTRL_UNLOCK: Byte = 0x01
    const val CTRL_LOCK: Byte = 0x02
    const val CTRL_CAR_LOCATOR: Byte = 0x03
    const val CTRL_HOOD_UNLOCK: Byte = 0x04
    const val CTRL_PANIC_SEARCH: Byte = 0x05
    const val CTRL_TRUNK_UNLOCK: Byte = 0x06
    const val CTRL_TRUNK_LOCK: Byte = 0x07
    const val CTRL_ENGINE_REMOTE_START: Byte = 0x08
    const val CTRL_KEY_INSIDE: Byte = 0x09
    const val CTRL_RPA_START: Byte = 0x0A
    const val CTRL_KEY_OUTSIDE: Byte = 0x0B
    const val CTRL_WINDOW_UP: Byte = 0x0C
    const val CTRL_WINDOW_DOWN: Byte = 0x0D
    const val CTRL_VENTILATION: Byte = 0x12
    const val CTRL_CHARGE_LID: Byte = 0x13
    const val CTRL_UNDEFINED: Byte = 0xFF.toByte()

    // ---- CustomControlType.type — the 1st body byte of a 0x0151 CUST_REQ (CustomPayload.type) ----
    // Verified against stock CustomControlType.smali: APPROACH_UNLOCK type=1, WALK_AWAY_LOCK type=2.
    // The 2nd body byte (CustomPayload.data[0]) is the on/off flag: 1 = enable, 0 = disable.
    // These are the ONLY wire form for walk-away-lock / approach-unlock — there is NO cloud/TSP path
    // (sendCustomCmd for these two types delegates solely to BLE, no HTTP fallback).
    const val CUST_TYPE_APPROACH_UNLOCK: Byte = 0x01
    const val CUST_TYPE_WALK_AWAY_LOCK: Byte = 0x02
    const val CUST_ENABLE: Byte = 0x01
    const val CUST_DISABLE: Byte = 0x00

    // ---- GATT UUIDs (service family 0236xxxx-CF3A-11E1-EFDE-0002A5D5C51B) ----
    const val SERVICE_UUID = "02362AFF-CF3A-11E1-EFDE-0002A5D5C51B"
    const val CHAR_CH1_WRITE = "02362A10-CF3A-11E1-EFDE-0002A5D5C51B"   // phone -> car
    const val CHAR_CH1_NOTIFY = "02362A11-CF3A-11E1-EFDE-0002A5D5C51B"  // car -> phone
    const val CHAR_CH2_WRITE = "02362A12-CF3A-11E1-EFDE-0002A5D5C51B"   // coef upload
    const val CHAR_CH2_NOTIFY = "02362A13-CF3A-11E1-EFDE-0002A5D5C51B"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * cmdIds whose body is AES-128-GCM encrypted. Verified against captures:
     * the DK-key upload (0x010b) is GCM (body size = GCM(nSeq+ts+digitalKey)),
     * control/status/RPA are GCM; the plaintext ones are the pre-key handshake
     * (cert/factor exchange 0x0103/04/06/07/08), connect-confirm/pre-sync, and
     * the coef/calibration frames (0x0171-0x0173, coefSmall is visible on wire).
     */
    fun isEncrypted(cmdId: Int): Boolean = when (cmdId) {
        CMD_A2V_SEND_DKEY, CMD_V2A_DK_VERIFY_STATUS,
        CMD_A2V_CONTROL, CMD_V2A_CMD_RECEIVED, CMD_V2A_RESULT,
        CMD_A2V_RPA_REQ, CMD_V2A_RPA_STATUS, CMD_V2A_RPA_CHALLENGE, CMD_A2V_RPA_ANSWER,
        CMD_V2A_RPA_SYNC, CMD_V2A_RPA_SYNC2, CMD_A2V_TRANS, CMD_V2A_VSTATUS_SYNC,
        CMD_A2V_CUST_REQ, CMD_V2A_CUST_RESP, CMD_V2A_APPROACHLOCK_NOTIFY -> true
        // NB: CMD_A2V_RSSI_SYNC (0x0158) is sent PLAINTEXT — the stock RSSI packer (p0/f0.b)
        // returns the payload unencrypted (nSeq|ts|signal), unlike the other RPA frames.
        else -> false
    }

    /**
     * cmdIds whose GCM plaintext carries a trailing 6-byte AES-CMAC (remote parking).
     * The MAC is over `ts(4 BE) ‖ controlBytes` (nSeq excluded); trailer = AES-CMAC[0:6].
     * Only 0x0113 (RPA_REQ) and 0x0116 (RPA_ANSWER) — lock/unlock 0x0110 has no CMAC.
     * See CMAC_FINDINGS.md §1.
     */
    fun needsCmac(cmdId: Int): Boolean = cmdId == CMD_A2V_RPA_REQ || cmdId == CMD_A2V_RPA_ANSWER

    /**
     * Frames that ride GATT channel 2 (char 2A12/2A13). Everything else — including the
     * BIG-calibration upload and normal control — rides channel 1 (2A10/2A11).
     *
     * VERIFIED against the stock sender n0/g:
     *  - small-calib (0x0172) send site (line ~3850) uses `ChnType.UUID2` (channel 2);
     *  - big-calib (0x0171) loop (line ~3158) uses `ChnType.UUID1` (channel 1) — an earlier mapping
     *    that put BIG on channel 2 was wrong;
     *  - CUST_REQ (0x0151) `n0/g.D` (line ~729) uses `ChnType.UUID2` (channel 2). This is the
     *    walk-away-lock / approach-unlock custom command.
     */
    fun isChannel2(cmdId: Int): Boolean = when (cmdId) {
        CMD_A2V_SMALL_CALIBRATION_DATA, CMD_V2A_SMALL_CALIBRATION_DATA_RESP,
        CMD_A2V_CUST_REQ, CMD_V2A_CUST_RESP -> true
        else -> false
    }

    /**
     * Application-layer package size for the fragmented 0x0171 BIG-calibration upload
     * (the `calibrationParam` slice carried in each BigCalibrationPayload).
     *
     * The car reassembles by `pakegeIndex`/`pakegeSum` regardless of slice size, so this is
     * NOT protocol-critical — any consistent split works. Stock (p0/f split) chooses
     * `negotiatedMtu − 19` so each package is exactly one ATT write (frame overhead =
     * header(7) + nSeq/ts(6) + pakegeSum(2) + pakegeIndex(2) + CRC(2) = 19). We can't read the
     * live MTU from the session layer, so we use a fixed conservative size that fits a typical
     * negotiated MTU in a single write; on a smaller MTU the GATT layer transparently fragments
     * (the car still reassembles by the frame `len`).
     */
    const val BIG_CALIB_APP_CHUNK = 220
}
