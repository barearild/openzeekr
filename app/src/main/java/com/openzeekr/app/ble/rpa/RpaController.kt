package com.openzeekr.app.ble.rpa

import com.openzeekr.app.ble.DkLockController
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
 * The full protocol structure is real:
 *   - RPA request frames carry a single control byte (see [RpaReq]).
 *   - The car issues CMD_V2A_RPA_CHALLENGE each round; we auto-answer with
 *     CMD_A2V_RPA_ANSWER (grid lookup — table not yet extracted, see DkSession).
 *   - The hold-to-move joystick sends CMD_RSPA_FORWARD/BACKWARD every 500 ms;
 *     finger-up sends CMD_RSPA_BOTTOM_RELEASE and cancels the heartbeat.
 *   - RSSI is streamed via CMD_A2V_RSSI_SYNC for the car's proximity gate.
 *
 * The crypto wrapping lives in [DkSession]; until that is reversed, sends throw
 * NotYetReversed — but the control flow, opcodes and heartbeat are complete.
 */
class RpaController(
    private val session: DkSession,
    private val scope: CoroutineScope,
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

    init {
        session.onInbound { cmd, payload -> handleInbound(cmd, payload) }
    }

    /** Establish the DK session and request RPA mode. */
    fun begin() {
        scope.launch {
            _state.value = _state.value.copy(phase = Phase.CONNECTING)
            runCatching {
                if (!session.isEstablished) session.establish()
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, byteArrayOf(RpaReq.CMD_RPA_REQ_MODE))
            }.onSuccess {
                _state.value = _state.value.copy(phase = Phase.READY)
            }.onFailure { fail(it) }
        }
    }

    fun startParkIn() = oneShot(RpaReq.CMD_RPA_START_PARKING_IN, Phase.PARKING_IN)
    fun searchSlot() = oneShot(RpaReq.CMD_RPA_START_SEARCHING_SLOT, Phase.PARKING_IN)
    fun continueParking() = oneShot(RpaReq.CMD_RPA_CONTINUE, Phase.PARKING_IN)
    fun pause() = oneShot(RpaReq.CMD_RPA_PAUSE, Phase.PAUSED)
    fun undo() = oneShot(RpaReq.CMD_RPA_UNDO, Phase.READY)

    fun stop() {
        stopHeartbeat()
        oneShot(RpaReq.CMD_RPA_STOP, Phase.READY)
    }

    /** Set park-out direction then start the park-out maneuver. */
    fun startParkOut(direction: Byte) {
        scope.launch {
            runCatching {
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    byteArrayOf(RpaReq.CMD_RPA_OUT_MODE_SET, direction))
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ,
                    byteArrayOf(RpaReq.CMD_RPA_START_PARKING_OUT))
            }.onSuccess { _state.value = _state.value.copy(phase = Phase.PARKING_OUT) }
                .onFailure { fail(it) }
        }
    }

    // ---- RSPA hold-to-move (dead-man's switch) ----

    /** Press-and-hold: begins the 500 ms heartbeat sending [forward]/backward. */
    fun holdMove(forward: Boolean) {
        if (heartbeat?.isActive == true) return
        _state.value = _state.value.copy(phase = Phase.MOVING)
        val ctrl = if (forward) RpaReq.CMD_RSPA_FORWARD else RpaReq.CMD_RSPA_BACKWARD
        heartbeat = scope.launch {
            while (isActive) {
                runCatching {
                    session.sendFrame(
                        DkOpcodes.CMD_A2V_RPA_REQ,
                        byteArrayOf(ctrl, phoneStatusByte()),
                    )
                }.onFailure { fail(it); return@launch }
                delay(RpaConst.HEARTBEAT_MS)
            }
        }
    }

    /** Finger-up: send BOTTOM_RELEASE and cancel the heartbeat. */
    fun releaseMove() {
        stopHeartbeat()
        scope.launch {
            runCatching {
                session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, byteArrayOf(RpaReq.CMD_RSPA_BOTTOM_RELEASE))
            }
            _state.value = _state.value.copy(phase = Phase.READY)
        }
    }

    /** Stream the phone's BLE RSSI to the car for its proximity gate. */
    fun reportRssi(rssi: Int) {
        scope.launch {
            runCatching {
                session.sendFrame(DkOpcodes.CMD_A2V_RSSI_SYNC, intToBytes(rssi))
            }
        }
    }

    // ---- internals ----

    private fun oneShot(ctrl: Byte, phase: Phase) {
        scope.launch {
            runCatching { session.sendFrame(DkOpcodes.CMD_A2V_RPA_REQ, byteArrayOf(ctrl)) }
                .onSuccess { _state.value = _state.value.copy(phase = phase) }
                .onFailure { fail(it) }
        }
    }

    private fun handleInbound(cmd: Int, payload: ByteArray) {
        when (cmd) {
            DkOpcodes.CMD_V2A_RPA_CHALLENGE -> {
                // Auto-answer the per-round anti-relay challenge.
                if (payload.size >= 2) {
                    val x = payload[0].toInt() and 0xff
                    val y = payload[1].toInt() and 0xff
                    scope.launch {
                        runCatching {
                            val answer = session.answerChallenge(x, y)
                            session.sendFrame(DkOpcodes.CMD_A2V_RPA_ANSWER, answer)
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
        stopHeartbeat()
        _state.value = _state.value.copy(phase = Phase.ERROR, message = t.message)
    }

    private fun phoneStatusByte(): Byte = PhoneStatus.NORMAL.code.toByte()

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
