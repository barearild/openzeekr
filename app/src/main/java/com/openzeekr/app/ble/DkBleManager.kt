package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns BLE scanning/connection to the vehicle and exposes a [DkSession].
 *
 * The GATT service/characteristic UUIDs for the DK channel and the exact frame
 * layout are part of the not-yet-finished DK reversing, so [connect] wires the
 * scaffolding and marks the remaining unknowns with TODO(dk). The public shape
 * (scan -> connect -> session) is stable for the UI/controllers to build on.
 */
class DkBleManager(private val appContext: Context) : DkTransport {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SESSION_READY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var inboundHandler: ((Int, ByteArray) -> Unit)? = null

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    val bluetoothAvailable: Boolean get() = adapter?.isEnabled == true

    /** Session bound to this transport; placeholder until the handshake is reversed. */
    val session: DkSession by lazy { PlaceholderDkSession(this) }

    @SuppressLint("MissingPermission")
    fun connect(deviceMac: String?) {
        if (adapter == null) { _state.value = State.ERROR; return }
        _state.value = State.SCANNING
        // TODO(dk): scan for the vehicle's DK advertisement (service UUID unknown),
        //           connect GATT, discover the DK write/notify characteristics, then
        //           call session.establish(). Left unimplemented pending DK reversing.
        _state.value = State.CONNECTING
    }

    fun disconnect() {
        runCatching { session.close() }
        _state.value = State.IDLE
    }

    // ----- DkTransport -----

    @SuppressLint("MissingPermission")
    override suspend fun write(cmd: Int, framed: ByteArray): Boolean {
        // TODO(dk): writeCharacteristic on the DK write characteristic with the
        //           opcode-tagged frame. No-op until GATT is wired.
        throw NotYetReversedException("DK GATT characteristic write")
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { inboundHandler = handler }

    /** Called by the (future) GATT notify callback to dispatch inbound frames. */
    internal fun dispatchInbound(cmd: Int, payload: ByteArray) { inboundHandler?.invoke(cmd, payload) }

    override fun close() { inboundHandler = null }

    companion object {
        @Volatile private var INSTANCE: DkBleManager? = null
        fun get(context: Context): DkBleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DkBleManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
