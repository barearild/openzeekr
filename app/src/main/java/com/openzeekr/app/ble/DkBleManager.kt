package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns BLE scan/connect to the vehicle and exposes a real [DkSession].
 *
 * Flow: scan (service UUID) or connect by MAC -> request MTU -> discover ->
 * enable notify on 2A11 & 2A13 -> (credential present) establish DK session.
 * Implements [DkTransport]: writes are GATT-fragmented (see [DkFragmenter]) and
 * notifications are reassembled + CRC-checked ([DkReassembler]) into DK frames.
 */
class DkBleManager(private val appContext: Context) : DkTransport {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SESSION_READY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    @Volatile var lastError: String? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inboundHandler: ((Int, ByteArray) -> Unit)? = null

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val bluetoothAvailable: Boolean get() = adapter?.isEnabled == true

    // ---- session (stable instance; reads the credential at establish() time) ----
    @Volatile private var credential: DkCredential? = null

    /** Single stable session so Deps/controllers capture it once; needs a credential to establish(). */
    val session: DkSession by lazy { RealDkSession(this, { credential }) }

    /** Provide provisioned key material (from cloud provisioning / import). */
    fun setCredential(cred: DkCredential) { credential = cred }

    // ---- GATT state ----
    private var gatt: BluetoothGatt? = null
    private var chWrite1: BluetoothGattCharacteristic? = null
    private var chWrite2: BluetoothGattCharacteristic? = null
    private var chNotify1: BluetoothGattCharacteristic? = null
    private var chNotify2: BluetoothGattCharacteristic? = null
    private val reasm1 = DkReassembler()
    private val reasm2 = DkReassembler()
    private var maxChunk = 20
    private val writeLock = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var notifyStep: CompletableDeferred<Boolean>? = null

    // ---- scan state ----
    private var scanCb: ScanCallback? = null
    private var scanJob: Job? = null
    private val seenAdvertisers = mutableSetOf<String>()
    /** 8-byte broadcast-random from the matched car's advertisement (see [parseBroadcastRnd]). */
    @Volatile private var advBroadcastRnd: ByteArray? = null
    /** Per-MAC broadcast-random seen during this scan (the DK mfr-data advert is separate from
     *  the name advert and can arrive in a different PDU / be dropped at low RSSI). */
    private val rndByMac = mutableMapOf<String, ByteArray>()

    // ---------------- connect ----------------

    @SuppressLint("MissingPermission")
    fun connect(deviceMac: String?) {
        Logx.d("ble", "connect(${deviceMac ?: "scan-by-service"}) credential=${if (credential != null) "present" else "none"}")
        val a = adapter ?: run { fail("no bluetooth adapter"); return }
        if (!a.isEnabled) { fail("bluetooth disabled"); return }
        lastError = null
        // Never hard-crash on a missing runtime permission (BLUETOOTH_SCAN/CONNECT
        // on API 31+) — surface it as an error state instead. The UI requests the
        // permission before calling this, but guard defensively.
        try {
            if (deviceMac != null) {
                _state.value = State.CONNECTING
                connectDevice(a.getRemoteDevice(deviceMac))
            } else {
                startScan(a)
            }
        } catch (e: SecurityException) {
            fail("missing Bluetooth permission (grant BLUETOOTH_SCAN/CONNECT): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(a: BluetoothAdapter) {
        val scanner = a.bluetoothLeScanner ?: run { fail("no LE scanner"); return }
        _state.value = State.SCANNING
        seenAdvertisers.clear()
        rndByMac.clear()
        advBroadcastRnd = null
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val target = UUID.fromString(DkProtocol.SERVICE_UUID)
        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val addr = dev.address ?: return
                val rec = result.scanRecord
                val name = rec?.deviceName ?: runCatching { dev.name }.getOrNull()
                val uuids = rec?.serviceUuids
                // Log every distinct advertiser once — so the car can be identified
                // by name/MAC even if it doesn't advertise the DK service UUID.
                if (seenAdvertisers.add(addr)) {
                    Logx.d("ble", "adv $addr rssi=${result.rssi} name=${name ?: "?"} " +
                        "uuids=${uuids?.joinToString { it.uuid.toString() } ?: "none"}")
                }
                // The DK broadcast-random rides a separate manufacturer-data advert PDU that can
                // arrive apart from the name PDU (or drop at low RSSI). Capture it per-MAC from
                // EVERY advert so it's available whichever PDU triggers the name match.
                if (rndByMac[addr] == null) parseBroadcastRnd(rec?.bytes)?.let {
                    rndByMac[addr] = it
                    Logx.d("ble", "broadcastRnd[$addr]=${it.joinToString("") { b -> "%02x".format(b) }}")
                }
                // The car advertises name "Zeekr<last-3-of-VIN>" (e.g. Zeekr662) and
                // does NOT advertise the 128-bit GATT service UUID, so match by name
                // (like the stock fastble client). Keep the UUID match as a fallback.
                val matchesName = name?.startsWith("Zeekr", ignoreCase = true) == true
                val matchesUuid = uuids?.any { it.uuid == target } == true
                if (matchesName || matchesUuid) {
                    // Only connect once we actually hold the broadcast-random for this car — it's
                    // required to derive the 0x0101 pairing connectKey. If the matching PDU didn't
                    // carry it yet, keep scanning; a subsequent mfr-data advert will fill rndByMac.
                    val rnd = rndByMac[addr]
                    if (rnd == null) {
                        Logx.d("ble", "matched ${if (matchesName) "name '$name'" else "uuid"} $addr but no " +
                            "broadcastRnd yet — waiting for the DK mfr-data advert…")
                        return
                    }
                    advBroadcastRnd = rnd
                    Logx.d("ble", "match ${if (matchesName) "by name '$name'" else "by service uuid"} " +
                        "rnd=${rnd.joinToString("") { "%02x".format(it) }} -> connecting $addr")
                    stopScanInternal(scanner)
                    _state.value = State.CONNECTING
                    connectDevice(dev)
                }
            }
            override fun onScanFailed(errorCode: Int) { stopScanInternal(scanner); fail("scan failed: $errorCode") }
        }
        scanCb = cb
        // No filter -> receive ALL advertisers (the car likely does NOT advertise
        // the 128-bit GATT service UUID; fastble in the stock app matches by name).
        Logx.d("ble", "scanning (no filter) — logging all advertisers; matching DK service ${DkProtocol.SERVICE_UUID}")
        scanner.startScan(null, settings, cb)
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_state.value == State.SCANNING) {
                stopScanInternal(scanner)
                fail("no DK device matched in ${SCAN_TIMEOUT_MS / 1000}s — check the advertiser log above " +
                    "for the car's name/MAC, then connect by MAC")
            }
        }
    }

    /**
     * Extract the 8-byte broadcast-random from a raw BLE advertisement, matching
     * `o0/a.a` + `BroadCastPacket.fromBin` in the stock app:
     *   walk AD structures [len][type][payload]; the DK advert is len=0x15, type=0xFF
     *   (manufacturer-specific). Its 20-byte packet has cryptedId at [8:20]; the
     *   broadcast-random = cryptedId[4:12] = packet[12:20].
     */
    private fun parseBroadcastRnd(record: ByteArray?): ByteArray? {
        if (record == null) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > record.size) break            // need [type + (len-1) payload]
            val type = record[i + 1].toInt() and 0xFF
            if (len == 0x15 && type == 0xFF) {
                val packet = record.copyOfRange(i + 2, i + 1 + len)   // 20 bytes after the type
                if (packet.size >= 20) return packet.copyOfRange(12, 20)
            }
            i += len + 1
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(scanner: android.bluetooth.le.BluetoothLeScanner) {
        scanJob?.cancel(); scanJob = null
        scanCb?.let { runCatching { scanner.stopScan(it) } }; scanCb = null
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        adapter?.bluetoothLeScanner?.let { runCatching { stopScanInternal(it) } }
        runCatching { session.close() }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        reasm1.reset(); reasm2.reset()
        _state.value = State.IDLE
    }

    // ---------------- GATT callbacks ----------------

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Logx.d("ble", "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = State.CONNECTED
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (_state.value != State.SESSION_READY) fail("disconnected (status=$status)")
                else _state.value = State.IDLE
                runCatching { g.close() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            maxChunk = (mtu - 3).coerceAtLeast(20)
            Logx.d("ble", "mtu=$mtu maxChunk=$maxChunk -> discoverServices")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(DkProtocol.SERVICE_UUID)) ?: run { fail("DK service not found"); return }
            chWrite1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_WRITE))
            chNotify1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_NOTIFY))
            chWrite2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_WRITE))
            chNotify2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_NOTIFY))
            Logx.d("ble", "services discovered: ch1w=${chWrite1 != null} ch1n=${chNotify1 != null} " +
                "ch2w=${chWrite2 != null} ch2n=${chNotify2 != null}")
            if (chWrite1 == null || chNotify1 == null) { fail("DK characteristics missing"); return }
            scope.launch { setupNotificationsAndEstablish(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            notifyStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onNotify(ch.uuid, ch.value ?: ByteArray(0))
        }
        // API >= 33
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(ch.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun setupNotificationsAndEstablish(g: BluetoothGatt) {
        if (!enableNotify(g, chNotify1!!)) { fail("enable notify 2A11 failed"); return }
        chNotify2?.let { if (!enableNotify(g, it)) Log.w(TAG, "enable notify 2A13 failed (continuing)") }
        val cred = credential
        if (cred == null) {
            Logx.w("ble", "connected but no credential — provision a key first (session not established)")
            _state.value = State.CONNECTED; return
        }
        try {
            Logx.d("ble", "starting DK handshake …")
            (session as RealDkSession).establish()
            Logx.d("ble", "DK session READY")
            _state.value = State.SESSION_READY
        } catch (e: Exception) {
            fail("DK handshake: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(DkProtocol.CCCD_UUID)) ?: return false
        val step = CompletableDeferred<Boolean>(); notifyStep = step
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = enable; g.writeDescriptor(cccd) }
        }
        return withTimeoutOrNull(4000) { step.await() } ?: false
    }

    private fun onNotify(uuid: UUID, bytes: ByteArray) {
        val reasm = if (uuid.toString().equals(DkProtocol.CHAR_CH2_NOTIFY, true)) reasm2 else reasm1
        val frameBytes = reasm.feed(bytes) ?: return
        try {
            val f = DkFrame.decode(frameBytes)
            Logx.d("ble", "<- frame cmd=0x${f.cmdId.toString(16)} body=${f.body.size}B " +
                "hex=${f.body.take(64).joinToString("") { "%02x".format(it) }}")
            inboundHandler?.invoke(f.cmdId, f.body)
        } catch (e: DkFrameException) {
            Logx.w("ble", "bad inbound frame: ${e.message} raw=${frameBytes.take(48).joinToString("") { "%02x".format(it) }}")
        }
    }

    // ---------------- DkTransport ----------------

    @SuppressLint("MissingPermission")
    override suspend fun write(cmd: Int, framed: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return false
        val ch = (if (DkProtocol.isChannel2(cmd)) chWrite2 else chWrite1) ?: return false
        Logx.d("ble", "-> frame cmd=0x${cmd.toString(16)} ${framed.size}B")
        for (chunk in DkFragmenter.split(framed, maxChunk)) {
            val ack = CompletableDeferred<Boolean>(); writeAck = ack
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = chunk
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok) return false
            if (withTimeoutOrNull(4000) { ack.await() } != true) return false
        }
        return true
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { inboundHandler = handler }

    override fun broadcastRnd(): ByteArray? = advBroadcastRnd

    override fun close() { inboundHandler = null }

    private fun fail(msg: String) { lastError = msg; Logx.e("ble", msg); _state.value = State.ERROR }

    companion object {
        private const val TAG = "DkBleManager"
        private const val SCAN_TIMEOUT_MS = 20_000L
        @Volatile private var INSTANCE: DkBleManager? = null
        fun get(context: Context): DkBleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DkBleManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
