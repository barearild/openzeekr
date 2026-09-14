package com.openzeekr.app.ble

import com.openzeekr.app.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Zeekr DK BLE session — pure Kotlin, no native libs.
 *
 * Handshake (all P-256 / AES-128-GCM / ECDSA-SHA256, see dk_ble_impl_spec.md):
 *   0x0103 send app DK cert            -> 0x0104 vehicle cert
 *   0x0106 send ephemeral factor+sig   -> 0x0108 vehicle factor (verify, derive sKey/iv)
 *   0x010b send digitalKey (GCM)       -> 0x010c verify status
 *   0x0101 connect-confirm
 *   0x0172 coef upload (ch2, plaintext)-> 0x0173 resp
 * then control frames 0x0110 (GCM) are accepted.
 *
 * Session keys: shared = ECDH(ephemeralPriv, vehicleFactor) = X(32)||Y(32);
 *   AES-128-GCM key = X[0:16], static GCM IV = Y[0:12]. No CMAC (GCM tag + CRC16).
 */
class RealDkSession(
    private val transport: DkTransport,
    private val credentialProvider: () -> DkCredential?,
    private val timeoutMs: Long = 8000,
) : DkSession {

    override var isEstablished: Boolean = false
        private set

    private var cryptoReady = false
    private lateinit var sKey: ByteArray   // 16
    private lateinit var iv: ByteArray     // 12
    private lateinit var ephemeral: KeyPair
    private var appHandler: ((Int, ByteArray) -> Unit)? = null

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    /** 16-byte AES-CMAC key for RPA frames (ECIES-unwrapped once from the credential). */
    @Volatile private var cmacKeyCache: ByteArray? = null

    init { transport.onInbound(::onRawInbound) }

    // ---------------- handshake ----------------

    override suspend fun establish() {
        if (isEstablished) return
        val cred = credentialProvider()
            ?: throw IllegalStateException("no DK credential provisioned (enrol + key-info first)")
        DkPayload.resetSeq()
        cryptoReady = false

        // 0) PAIRING EPOCH — 0x0101 CONNECT_CONFIRM -> 0x0102 DK_STATUS (AES-128-CBC
        //    under connectKey = VIN ⊕ hex(broadcastRnd)). The 0x0102 carries an errCode
        //    (ErrCode enum) and the car's disposer q0/q.a branches ONLY on it:
        //      0x1012 EEC_authenticated    = already paired -> reconnect (skip cert)
        //      0x1011 EEC_notAuthenticated = registered, first-pair -> send our cert
        //      0x1010 EEC_confirmFailed    = the car has NO registration for our (dkId,
        //                                    deviceId): the cloud->vehicle push hasn't landed.
        //    initState is ALWAYS 0 on the wire (isInitState() hardcoded 0; the "bond" is not
        //    created by a phone flag — it comes from the cloud->vehicle push before BLE).
        val rnd = transport.broadcastRnd()
            ?: throw IllegalStateException(
                "no broadcastRnd from advertisement — scan the car (don't connect by MAC) so the " +
                "0x0101 connectKey can be derived")
        val connectKey = DkCrypto.deriveConnectKey(cred.vin, rnd)
        Logx.d("dk", "handshake 0/5 connect-confirm (0x0101) rnd=${hexOf(rnd)} …")
        // The car's DK module can answer 0x100c EEC_busy ("BNCM Busy") when it's momentarily
        // occupied (waking, or tearing down a stale session). Stock (n0/g) does NOT abort on
        // this: it keeps the BLE link, logs "EEC_busy Wait 2.5s ReStart", waits 0x9c4=2500ms
        // and re-sends CONNECT_CONFIRM. Match that exactly rather than disconnecting.
        var err = connectConfirm(cred, rnd, connectKey, initState = 0)
        var busyTries = 0
        while (err == 0x100c && busyTries < 6) {
            busyTries++
            Logx.d("dk", "handshake 0/5 EEC_busy (BNCM busy) — wait 2.5s, ReStart (#$busyTries)")
            delay(2500)
            err = connectConfirm(cred, rnd, connectKey, initState = 0)
        }
        // Branch exactly like the stock 0x0102 disposer (q0/q.a): the CAR's errCode alone
        // selects the path. initState is always 0 on the wire (isInitState() is hardcoded 0).
        //   0x1012 EEC_authenticated    -> already paired (reconnect)  -> skip cert
        //   0x1011 EEC_notAuthenticated -> first-pair                  -> send our cert
        //   else (0x1010 EEC_confirmFailed …) -> terminal: the vehicle has NO registration
        //        for this (dkId, deviceId); the cloud->vehicle key push hasn't landed on the car.
        val firstPair: Boolean = when (err) {
            0x1012 -> { Logx.d("dk", "handshake 0/5 authenticated (reconnect)"); false }
            0x1011 -> { Logx.d("dk", "handshake 0/5 notAuthenticated (first-pair) — sending cert"); true }
            else -> throw IllegalStateException(
                "CONNECT_CONFIRM rejected: DK_STATUS errCode=0x%04x".format(err ?: 0) +
                (if (err == 0x1010)
                    " (EEC_confirmFailed) — the vehicle has NO registration for this key/device yet. " +
                    "The cloud→vehicle key push hasn't reached the car. Wake/start the car so its DK " +
                    "module syncs its key list from the cloud, then retry."
                else if (err == 0x100c)
                    " (EEC_busy) — the car's DK module stayed busy after 6×2.5s retries. " +
                    "Another BLE session (the stock app's :dkservice, or a stale connection) is " +
                    "likely holding it. Force-stop the stock app and retry."
                else " (unexpected)"))
        }

        // A reconnect (0x1012) skips the cert exchange and goes straight to the factor
        // exchange. We can't reach it until first-pair works, so fail explicitly for now.
        if (!firstPair) throw IllegalStateException(
            "DK reconnect (0x1012 authenticated) flow not implemented yet — expected first-pair (0x1011)")

        // 1) mutual cert exchange (cleartext during pairing)
        Logx.d("dk", "handshake 1/5 cert exchange …")
        runCatching {
            val c = parseCert(cred.dkCertDer)
            Logx.d("dk", "our leaf cert: subj='${c.subjectX500Principal.name}' iss='${c.issuerX500Principal.name}' " +
                "serial=${c.serialNumber.toString(16)} sig=${c.sigAlgName} der=${cred.dkCertDer.size}B")
            Logx.d("dk", "our cert DER head=${hexOf(cred.dkCertDer.copyOfRange(0, minOf(48, cred.dkCertDer.size)))}")
        }.onFailure { Logx.w("dk", "our cert parse failed (sending anyway): ${it.message} der=${cred.dkCertDer.size}B " +
            "head=${hexOf(cred.dkCertDer.copyOfRange(0, minOf(32, cred.dkCertDer.size)))}") }
        val carCertBody = exchange(DkProtocol.CMD_A2V_SEND_APP_CERT, cred.dkCertDer,
            DkProtocol.CMD_V2A_SEND_VEHICLE_CERT)
        val carCert = parseCert(afterHeader(carCertBody))
        Logx.d("dk", "handshake 1/5 vehicle cert: ${carCert.subjectX500Principal.name.take(64)}")

        // 2) ephemeral factor + signature  (sign over nSeq||ts||factor; cleartext)
        Logx.d("dk", "handshake 2/5 send factor+sig …")
        ephemeral = DkCrypto.generateEcKeyPair()
        val factor = DkCrypto.factorBytes(ephemeral.public)              // X(32)||Y(32)
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val content = DkPayload.wrap(nSeq, ts, factor)                   // nSeq||ts||factor (70)
        val digest = DkCrypto.sha256(content)
        val sig = DkCrypto.ecdsaSignDer(cred.dkPrivateKey, content)
        val factorBody = content + digest + sig                         // full payload
        val vfBody = exchangeRaw(DkProtocol.CMD_A2V_SEND_APP_FACTOR, factorBody, false,
            DkProtocol.CMD_V2A_SEND_VEHICLE_FACTOR)

        // 3) verify vehicle factor + derive session keys (frames become GCM from here)
        Logx.d("dk", "handshake 3/5 verify factor + derive session keys …")
        deriveSession(vfBody, carCert)
        cryptoReady = true
        Logx.d("dk", "handshake 3/5 session keys derived (GCM ready)")
        // DIAGNOSTIC: dump session key material so the 0x010c decrypt can be analysed offline.
        Logx.d("dk", "DBG sKey=${hexOf(sKey)} iv=${hexOf(iv)} carFactorPt=${hexOf(vfBody.copyOfRange(6, 70))}")

        // 4) register the digital key (GCM) + confirm
        Logx.d("dk", "handshake 4/5 send digital key …")
        Logx.d("dk", "DBG dkey plaintext(${cred.digitalKey.size}B)=${hexOf(cred.digitalKey)}")
        runCatching {
            exchange(DkProtocol.CMD_A2V_SEND_DKEY, cred.digitalKey, DkProtocol.CMD_V2A_DK_VERIFY_STATUS)
        }.onFailure { Logx.w("dk", "SEND_DKEY: ${it.message}") }

        // 5) coef upload on channel 2 (plaintext) — optional (RPA/approach only)
        Logx.d("dk", "handshake 5/5 coef upload …")
        runCatching {
            exchange(DkProtocol.CMD_A2V_SMALL_CALIBRATION_DATA, cred.coefSmall,
                DkProtocol.CMD_V2A_SMALL_CALIBRATION_DATA_RESP)
        }.onFailure { Logx.w("dk", "coef upload: ${it.message}") }

        isEstablished = true
        Logx.d("dk", "=== DK session established ===")
    }

    /**
     * Send 0x0101 CONNECT_CONFIRM with the given [initState], await 0x0102 DK_STATUS,
     * decrypt it (same connectKey + cbcIv) and return the car's errCode (ErrCode enum),
     * or null if it couldn't be read. Throws only if 0x0102 fails to decrypt (wrong key).
     */
    private suspend fun connectConfirm(cred: DkCredential, rnd: ByteArray, connectKey: ByteArray, initState: Int): Int? {
        val confirm = buildConfirmCodeNew(cred, rnd, initState)          // 47B plaintext
        val body = DkCrypto.aesCbcEncryptPkcs7(connectKey, DkCrypto.CBC_IV, confirm)
        val rx = exchangeRaw(DkProtocol.CMD_A2V_CONNECT_CONFIRM, body, false, DkProtocol.CMD_V2A_DK_STATUS)
        val status = try {
            DkCrypto.aesCbcDecryptPkcs7(connectKey, DkCrypto.CBC_IV, rx)
        } catch (e: Exception) {
            throw IllegalStateException("0x0102 decrypt failed (wrong VIN or broadcastRnd?): ${e.message}")
        }
        val err = if (status.size >= 8) ((status[6].toInt() and 0xFF) shl 8) or (status[7].toInt() and 0xFF) else null
        Logx.d("dk", "handshake 0/5 DK_STATUS (initState=$initState) errCode=${err?.let { "0x%04x".format(it) } ?: "?"} " +
            "status=${hexOf(status)}")
        return err
    }

    /**
     * Build the 43-byte `ConfirmCodePayloadNew` plaintext for 0x0101 (byte-exact per `p0/n` +
     * `ConfirmCodePayloadNew.toBin`, VERIFIED by decrypting the stock 0x0101):
     *   nSeq(2) ‖ ts(4) ‖ initState(1) ‖ dkID(4) ‖ sha(8) ‖ phoneId(8) ‖
     *   phoneType(3) ‖ bigCalibHash(4) ‖ smallCalibHash(4) ‖ selfCalibHash(4) ‖ calibType(1)
     * where dkID = hexToBytes(bookId) (4B, e.g. 8000a6af — NOT the numeric dkId),
     *       sha = SHA256(broadcastRnd)[idx:idx+8], idx = SHA256(broadcastRnd)[0] & 0x0f,
     *       phoneType = mobileCode bytes, big/smallCalibHash = SHA256(coef*Param)[0:4].
     * [initState] 0 = verify existing bond, 1 = first-time init / create bond.
     */
    private fun buildConfirmCodeNew(cred: DkCredential, rnd: ByteArray, initState: Int): ByteArray {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val digest = DkCrypto.sha256(rnd)
        val idx = digest[0].toInt() and 0x0f
        val sha = digest.copyOfRange(idx, idx + 8)
        return DkPayload.wrap(nSeq, ts,
            byteArrayOf((initState and 0xFF).toByte()) +   // initState
            cred.dkIdBytes +           // dkID = hexToBytes(bookId) (4B, e.g. 8000a6af) — NOT numeric dkId
            sha +                      // sha (8B)
            cred.phoneId8 +            // phoneId = deviceId[0:8]
            cred.phoneType3 +          // phoneType(3) = mobileCode bytes (stock p0/n)
            cred.bigCalibHash4 +       // bigCalibrationDataHash(4) = SHA256(coefBigParam)[0:4]
            cred.smallCalibHash4 +     // smallCalibrationDataHash(4) = SHA256(coefSmallParam)[0:4]
            ByteArray(4) +             // selfCalibrationDataHash(4) — no <vin>_SELF_CALIBRATION_HASH file on first pair
            byteArrayOf(0))            // calibrationType(1) = getCalibrationMode(vin) default 0
    }

    private fun deriveSession(vfBody: ByteArray, carCert: X509Certificate) {
        require(vfBody.size >= 6 + 64 + 32) { "vehicle factor too short (${vfBody.size})" }
        val content = vfBody.copyOfRange(0, 6 + 64)          // nSeq||ts||factor
        val carFactor = vfBody.copyOfRange(6, 6 + 64)
        val digest = vfBody.copyOfRange(70, 102)
        val sig = vfBody.copyOfRange(102, vfBody.size)
        check(DkCrypto.sha256(content).contentEquals(digest)) { "vehicle factor digest mismatch" }
        check(DkCrypto.ecdsaVerifyDer(carCert.publicKey, content, sig)) { "vehicle factor signature invalid" }
        val shared = DkCrypto.ecdhSharedPoint(ephemeral.private, carFactor)  // X(32)||Y(32)
        sKey = shared.copyOfRange(0, 16)
        iv = shared.copyOfRange(32, 44)
    }

    // ---------------- commands ----------------

    override suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean {
        if (!isEstablished) throw IllegalStateException("DK session not established")
        return send(cmd, payload)
    }

    override fun answerChallenge(randX: Int, randY: Int): ByteArray {
        val ans = RpaGrid.getAnswer(randX, randY)
        return byteArrayOf(((ans.toInt() ushr 8) and 0xFF).toByte(), (ans.toInt() and 0xFF).toByte())
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { appHandler = handler }

    override fun close() {
        reset()
        transport.close()
    }

    /**
     * Reset session state for a fresh handshake on the NEXT connect, WITHOUT tearing down
     * the transport wiring. The inbound handler is registered once in [init]; [close] nulls
     * it via `transport.close()`, which — because this session instance is reused across
     * reconnects — would permanently unwire inbound frames and silently break the next
     * handshake. So an unexpected BLE drop calls [reset], not [close]: it clears the stale
     * `isEstablished` flag and derived GCM keys (a new link needs a new pairing epoch + new
     * keys) but leaves the transport handler live so the re-handshake still receives frames.
     */
    fun reset() {
        isEstablished = false; cryptoReady = false; cmacKeyCache = null
        pending.values.forEach { it.cancel() }; pending.clear()
    }

    /**
     * The 16-byte AES-CMAC key for RPA (0x0113/0x0116) frames: ECIES-unwrap the credential's
     * cmacKeyCert with our own DK private key (CMAC_FINDINGS §7–8). On any failure fall back to
     * 0x55×16 — matching stock's behaviour when asymmDecrypt throws — so RPA still transmits and
     * the failure is visible in the log rather than crashing. Cached for the session.
     */
    private fun cmacKey(): ByteArray {
        cmacKeyCache?.let { return it }
        val cred = credentialProvider()
        val key = try {
            if (cred != null && cred.cmacKeyCert.isNotEmpty())
                DkCrypto.unwrapCmacKey(cred.cmacKeyCert, cred.dkPrivateKey).also {
                    Logx.d("dk", "RPA cmacKey unwrapped via ECIES (${it.size}B, cert=${cred.cmacKeyCert.size}B)")
                }
            else {
                Logx.w("dk", "RPA cmacKey: no cmacKeyCert on credential — using 0x55 fallback")
                DkCrypto.CMAC_FALLBACK_KEY
            }
        } catch (e: Exception) {
            Logx.w("dk", "RPA cmacKey ECIES unwrap failed (${e.message}) — using 0x55 fallback")
            DkCrypto.CMAC_FALLBACK_KEY
        }
        cmacKeyCache = key
        return key
    }

    // ---------------- frame I/O ----------------

    /** Build (auto nSeq/ts, 6-byte CMAC trailer for RPA, GCM if needed) and write; no wait. */
    private suspend fun send(cmdId: Int, tail: ByteArray): Boolean {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        // RPA frames append AES-CMAC(cmacKey, ts(4 BE) ‖ tail)[0:6] inside the GCM plaintext.
        val fullTail = if (DkProtocol.needsCmac(cmdId)) tail + DkCrypto.aesCmac6(cmacKey(), tsBytes(ts) + tail) else tail
        val plain = DkPayload.wrap(nSeq, ts, fullTail)
        val body = if (DkProtocol.isEncrypted(cmdId)) DkCrypto.gcmEncrypt(sKey, iv, plain) else plain
        val frame = DkFrame(cmdId, DkProtocol.INST_REQ, body).encode()
        return transport.write(cmdId, frame)
    }

    /** The 4-byte big-endian timestamp exactly as DkPayload.wrap serializes it (for the CMAC input). */
    private fun tsBytes(ts: Int): ByteArray =
        byteArrayOf((ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte())

    /** Send (auto nSeq/ts wrap) then await [expect]; returns the decrypted response body (incl nSeq||ts). */
    private suspend fun exchange(cmdId: Int, tail: ByteArray, expect: Int): ByteArray {
        val nSeq = DkPayload.nextSeq(); val ts = DkPayload.timestamp()
        val plain = DkPayload.wrap(nSeq, ts, tail)
        return exchangeRaw(cmdId, plain, DkProtocol.isEncrypted(cmdId), expect)
    }

    /** Send a pre-built payload body (already nSeq||ts||…); optionally GCM; await [expect]. */
    private suspend fun exchangeRaw(cmdId: Int, plainBody: ByteArray, encrypt: Boolean, expect: Int): ByteArray {
        val def = CompletableDeferred<ByteArray>()
        pending[expect] = def
        try {
            val body = if (encrypt) DkCrypto.gcmEncrypt(sKey, iv, plainBody) else plainBody
            val frame = DkFrame(cmdId, DkProtocol.INST_REQ, body).encode()
            if (!transport.write(cmdId, frame)) throw IllegalStateException("write failed for cmd ${hex(cmdId)}")
            return withTimeout(timeoutMs) { def.await() }
        } finally {
            pending.remove(expect)
        }
    }

    /** Called by the transport for each reassembled+CRC-checked inbound frame. */
    private fun onRawInbound(cmdId: Int, rawBody: ByteArray) {
        val body = try {
            if (cryptoReady && DkProtocol.isEncrypted(cmdId)) DkCrypto.gcmDecrypt(sKey, iv, rawBody) else rawBody
        } catch (e: Exception) {
            Logx.w("dk", "decrypt ${hex(cmdId)} failed: ${e.message} rawBody(${rawBody.size}B)=${hexOf(rawBody)}"); return
        }
        pending[cmdId]?.let { it.complete(body); return }
        // Known handshake failures: fail the awaited step immediately (don't wait for timeout).
        val failFor = when (cmdId) {
            DkProtocol.CMD_V2A_APP_CERT_VERIFY_FAILED -> DkProtocol.CMD_V2A_SEND_VEHICLE_CERT to "car rejected our cert (0x0105 CERT_FAIL)"
            DkProtocol.CMD_V2A_PARSE_APP_FACTOR_FAILED -> DkProtocol.CMD_V2A_SEND_VEHICLE_FACTOR to "car rejected our factor (0x0107)"
            else -> null
        }
        if (failFor != null) {
            Logx.w("dk", "${failFor.second}")
            pending[failFor.first]?.completeExceptionally(IllegalStateException(failFor.second))
            return
        }
        // unsolicited (status / RPA challenge / result): hand the tail (after nSeq||ts) to the app
        val tail = if (body.size >= 6) body.copyOfRange(6, body.size) else body
        appHandler?.invoke(cmdId, tail)
    }

    // ---------------- helpers ----------------

    private fun afterHeader(body: ByteArray) = if (body.size >= 6) body.copyOfRange(6, body.size) else body
    private fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    private fun hex(v: Int) = "0x%04x".format(v)
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** RpaCtrlCmd challenge-answer grid (RPA), extracted from smali. */
object RpaGrid {
    fun getAnswer(x: Int, y: Int): Short {
        val sub = x - y; val add = x + y; val mul = x * y; val div = if (y != 0) x / y else 0
        val v = when (x) {
            1 -> mul
            3 -> if (y in 2..6) sub else mul
            7 -> if (y >= 8) mul else if (y == 4) sub else add
            9 -> if (y <= 4) sub else if (y == 6 || y == 14) add else mul
            11 -> mul
            13 -> mul
            15 -> if (y == 4) sub else if (y == 2 || y == 6 || y == 12 || y == 14) add else mul
            17 -> mul
            19 -> mul
            21 -> when (y) { 2 -> sub; 4 -> add; 6 -> div; else -> mul }
            else -> 0
        }
        return v.toShort()
    }
}
