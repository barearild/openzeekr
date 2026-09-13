package com.openzeekr.app.ble.rpa

import com.openzeekr.app.ble.DkOpcodes
import com.openzeekr.app.ble.DkSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a remote-parking maneuver over the BLE DK session.
 *
 * Every phone→car frame is the single opcode CMD_A2V_RPA_REQ (0x0113) carrying a
 * 4-byte control block — `[rpaControl, rspaControl, outMode, phoneStatus]` — which
 * [DkSession.sendFrame] prepends nSeq/ts to and GCM-encrypts (same session crypto
 * as lock/unlock). Direction lives in **rspaControl**: FORWARD=0x03 / BACKWARD=0x02,
 * finger-up=BOTTOM_RELEASE(0x04). The move is a dead-man's switch: the control frame
 * is resent every 500 ms while held and the car halts the moment one is missed.
 *
 * The car issues CMD_V2A_RPA_CHALLENGE (0x0115) each round; we auto-answer with
 * CMD_A2V_RPA_ANSWER (0x0116) using the recovered getAnswer grid. RSSI is streamed
 * via CMD_A2V_RSSI_SYNC (0x0158) so the car's proximity gate keeps the maneuver live.
 */
class RpaController(
    private val session: DkSession,
    private val scope: CoroutineScope,
    /** Live phone-status byte packed into each frame (in-call / background gate). */
    private val phoneStatus: () -> Byte = { PhoneStatus.NORMAL.code.toByte() },
    /** Latest measured BLE RSSI of the car (dBm), streamed to its proximity gate. */
    private val rssi: (() -> Int?)? = null,
) {
    enum class Phase { IDLE, CONNECTING, READY, PARKING_IN, PARKING_OUT, MOVING, PAUSED, DONE, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val mode: Int = RpaConst.MODE_RSPA,
        val lastStatus: Byte? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var heartbeat: Job? = null
    private var rssiJob: Job? = null
    /** Current move direction (rspaControl), echoed as the challenge-answer gesture. */
    @Volatile private var gesture: Byte = 0

    init {
        session.onInbound { cmd, payload -> handleInbound(cmd, payload) }
    }

    /** The 4-byte RPA control block: rpaControl | rspaControl | outMode | phoneStatus. */
    private fun block(rpaCtrl: Byte, rspaCtrl: Byte = 0, outMode: Byte = 0): ByteArray =
        byteArrayOf(rpaCtrl, rspaCtrl, outMode, phoneStatus())

    /** Establish the DK session and enter RSPA straight-line (hold-to-move) mode. */
    fun begin() {
        scope.launch {
            _state.value = _state.value.copy(phase = Phase.CONNECTING, message = null)
            runCatching {
                if (!session.isEstablished) session.establish()
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_RPA_REQ_MODE, RpaReq.CMD_RSPA_REQUEST))
            }.onSuccess {
                _state.value = _state.value.copy(phase = Phase.READY)
                startRssiStream()
            }.onFailure { fail(it) }
        }
    }

    fun startParkIn() = oneShot(RpaReq.CMD_RPA_START_PARKING_IN, Phase.PARKING_IN)
    fun searchSlot() = oneShot(RpaReq.CMD_RPA_START_SEARCHING_SLOT, Phase.PARKING_IN)
    fun continueParking() = oneShot(RpaReq.CMD_RPA_CONTINUE, Phase.PARKING_IN)
    fun pause() = oneShot(RpaReq.CMD_RPA_PAUSE, Phase.PAUSED)
    fun undo() = oneShot(RpaReq.CMD_RPA_UNDO, Phase.READY)

    fun stop() {
        stopHeartbeat(); stopRssiStream(); gesture = 0
        oneShot(RpaReq.CMD_RPA_STOP, Phase.READY)
    }

    /** Set park-out direction then start the park-out maneuver. */
    fun startParkOut(direction: Byte) {
        scope.launch {
            runCatching {
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_RPA_OUT_MODE_SET, outMode = direction))
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_RPA_START_PARKING_OUT))
            }.onSuccess { _state.value = _state.value.copy(phase = Phase.PARKING_OUT) }
                .onFailure { fail(it) }
        }
    }

    // ---- RSPA hold-to-move (dead-man's switch) ----

    /** Press-and-hold: begins the 500 ms heartbeat with rspaControl = FORWARD/BACKWARD. */
    fun holdMove(forward: Boolean) {
        if (heartbeat?.isActive == true) return
        val rspa = if (forward) RpaReq.CMD_RSPA_FORWARD else RpaReq.CMD_RSPA_BACKWARD
        gesture = rspa
        _state.value = _state.value.copy(phase = Phase.MOVING)
        startRssiStream()
        heartbeat = scope.launch {
            while (isActive) {
                runCatching {
                    session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                        block(rpaCtrl = RpaReq.CMD_NONE, rspaCtrl = rspa))
                }.onFailure { fail(it); return@launch }
                delay(RpaConst.HEARTBEAT_MS)
            }
        }
    }

    /** Finger-up: send BOTTOM_RELEASE and cancel the heartbeat (car halts). */
    fun releaseMove() {
        stopHeartbeat()
        gesture = 0
        scope.launch {
            runCatching {
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    block(RpaReq.CMD_NONE, RpaReq.CMD_RSPA_BOTTOM_RELEASE))
            }
            if (_state.value.phase == Phase.MOVING) _state.value = _state.value.copy(phase = Phase.READY)
        }
    }

    /** Send one RSSI-sync frame (header + int32 BE, GCM). */
    fun reportRssi(rssiDbm: Int) {
        scope.launch {
            runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RSSI_SYNC, intToBytes(rssiDbm)) }
        }
    }

    // ---- internals ----

    private fun startRssiStream() {
        val provider = rssi ?: return
        if (rssiJob?.isActive == true) return
        rssiJob = scope.launch {
            while (isActive) {
                provider()?.let { reportRssi(it) }
                delay(RpaConst.HEARTBEAT_MS)
            }
        }
    }

    private fun stopRssiStream() { rssiJob?.cancel(); rssiJob = null }

    private fun oneShot(ctrl: Byte, phase: Phase) {
        scope.launch {
            runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, block(ctrl)) }
                .onSuccess { _state.value = _state.value.copy(phase = phase) }
                .onFailure { fail(it) }
        }
    }

    private fun handleInbound(cmd: Int, payload: ByteArray) {
        when (cmd) {
            DkOpcodes.CMD_V2A_RPA_CHALLENGE -> {
                // Auto-answer the per-round anti-relay challenge. The session strips the
                // nSeq/ts header, so payload = randX | randY | authStatus | …
                if (payload.size >= 2) {
                    val x = payload[0].toInt() and 0xff
                    val y = payload[1].toInt() and 0xff
                    scope.launch {
                        runCatching {
                            val ans = session.answerChallenge(x, y) // answer, 2 bytes BE
                            // 0x0116: phoneStatus | randX | randY | answer(2 BE) | gesture
                            val tail = byteArrayOf(
                                phoneStatus(), x.toByte(), y.toByte(),
                                ans.getOrElse(0) { 0 }, ans.getOrElse(1) { 0 }, gesture,
                            )
                            session.sendFrame(DkOpcodes.CMD_A2V_RPA_ANSWER, tail)
                        }.onFailure { fail(it) }
                    }
                }
            }
            else -> {
                val status = payload.firstOrNull()
                _state.value = _state.value.copy(lastStatus = status)
                if (status == RpaConst.RPA_OUT_OF_DISTANCE || status == RpaConst.RPA_SUSPEND) stopHeartbeat()
                if (status == RpaConst.RPA_COMPLETED) {
                    stopHeartbeat(); _state.value = _state.value.copy(phase = Phase.DONE)
                }
            }
        }
    }

    private fun stopHeartbeat() { heartbeat?.cancel(); heartbeat = null }

    private fun fail(t: Throwable) {
        stopHeartbeat(); stopRssiStream()
        _state.value = _state.value.copy(phase = Phase.ERROR, message = t.message)
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
