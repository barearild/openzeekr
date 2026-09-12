package com.openzeekr.app.ble

/**
 * The authenticated digital-key BLE session.
 *
 * Everything the app does over BLE (unlock, and remote parking) rides this
 * session: frames are AES-GCM encrypted with the session key and CMAC-signed
 * with the DK identity key (see the reversing notes, dk-crypto-byte-spec).
 *
 * The handshake + crypto are NOT fully reversed yet, so [PlaceholderDkSession]
 * throws NotYetImplemented. The interface is real, so the RPA / lock controllers
 * are written against it and become functional the moment a real implementation
 * (driven by the extracted secrets + libdk logic) is dropped in.
 */
interface DkSession {

    val isEstablished: Boolean

    /** Perform the BLE DK handshake (pair-verify / session-key derivation). */
    suspend fun establish()

    /**
     * Encrypt+sign [payload] for opcode [cmd] and write it to the car.
     * @return true if the frame was accepted for transmission.
     */
    suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean

    /**
     * Compute the RPA challenge answer. The car sends CMD_V2A_RPA_CHALLENGE with
     * (randX, randY); the app must reply via a fixed grid lookup
     * (RpaCtrlCmd.getAnswer/checkX/checkY), CMAC-signed. The lookup table is not
     * yet extracted — see [PlaceholderDkSession].
     */
    fun answerChallenge(randX: Int, randY: Int): ByteArray

    /** Latest inbound frame from the car (opcode -> payload), for status parsing. */
    fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit)

    fun close()
}

class NotYetReversedException(what: String) :
    UnsupportedOperationException("$what is not reverse-engineered yet — placeholder")

/**
 * Placeholder session. Wire-level structure is real; the crypto/handshake calls
 * throw so nothing silently pretends to work. Replace with a real impl backed by
 * the extracted DK secrets + libdk KDF once the handshake is reversed.
 */
class PlaceholderDkSession(private val transport: DkTransport) : DkSession {

    override var isEstablished: Boolean = false
        private set

    override suspend fun establish() {
        // TODO(dk): pair-verify + derive sKey = X‖Y, cbcIv, cmacKey from libdk KDF.
        throw NotYetReversedException("DK BLE handshake / session-key derivation")
    }

    override suspend fun sendFrame(cmd: Int, payload: ByteArray): Boolean {
        if (!isEstablished) throw NotYetReversedException("DK session (must establish() first)")
        // TODO(dk): AES-GCM(sessionKey) encrypt payload, append CMAC(identityKey), frame it.
        val framed = payload // <-- placeholder: real impl encrypts + signs here
        return transport.write(cmd, framed)
    }

    override fun answerChallenge(randX: Int, randY: Int): ByteArray {
        // TODO(dk): reproduce RpaCtrlCmd.getAnswer(x,y) grid table (not yet extracted),
        //           then CMAC-sign with the DK identity key.
        throw NotYetReversedException("RPA challenge-answer grid table")
    }

    override fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit) =
        transport.onInbound(handler)

    override fun close() { isEstablished = false; transport.close() }
}

/** Raw BLE GATT transport (frame in / frame out). Implemented by DkBleManager. */
interface DkTransport {
    suspend fun write(cmd: Int, framed: ByteArray): Boolean
    fun onInbound(handler: (cmd: Int, payload: ByteArray) -> Unit)
    fun close()
}
