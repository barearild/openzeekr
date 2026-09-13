package com.openzeekr.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * DK BLE frame codec (the layer above GATT fragmentation).
 *
 *   FE 03 | len(2 BE) | cmdId(2 BE) | instType(1) | body | CRC16/ARC(2 BE)
 *
 * `body` is plaintext for handshake frames and AES-128-GCM ciphertext for the
 * control/RPA/status frames (see [DkProtocol.isEncrypted]). This codec only does
 * framing + CRC; encryption is applied by the session before [encode].
 */
data class DkFrame(val cmdId: Int, val instType: Int, val body: ByteArray) {

    /** Serialize to on-wire bytes with header + CRC-16/ARC trailer. */
    fun encode(): ByteArray {
        val total = DkProtocol.HEADER_LEN + body.size + DkProtocol.CRC_LEN
        val buf = ByteArray(total)
        buf[0] = DkProtocol.MAGIC
        buf[1] = DkProtocol.VER_ZEEKR
        buf[2] = ((total ushr 8) and 0xFF).toByte()
        buf[3] = (total and 0xFF).toByte()
        buf[4] = ((cmdId ushr 8) and 0xFF).toByte()
        buf[5] = (cmdId and 0xFF).toByte()
        buf[6] = (instType and 0xFF).toByte()
        System.arraycopy(body, 0, buf, DkProtocol.HEADER_LEN, body.size)
        val crc = DkCrypto.crc16(buf, total - DkProtocol.CRC_LEN)
        buf[total - 2] = ((crc ushr 8) and 0xFF).toByte()
        buf[total - 1] = (crc and 0xFF).toByte()
        return buf
    }

    override fun equals(other: Any?) =
        other is DkFrame && cmdId == other.cmdId && instType == other.instType && body.contentEquals(other.body)

    override fun hashCode() = (cmdId * 31 + instType) * 31 + body.contentHashCode()

    companion object {
        /**
         * Parse & CRC-verify one complete reassembled frame.
         * @throws DkFrameException on bad magic, length, or CRC.
         */
        fun decode(buf: ByteArray): DkFrame {
            if (buf.size < DkProtocol.HEADER_LEN + DkProtocol.CRC_LEN)
                throw DkFrameException("frame too short: ${buf.size}")
            if (buf[0] != DkProtocol.MAGIC) throw DkFrameException("bad magic ${buf[0]}")
            val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (len != buf.size) throw DkFrameException("length $len != actual ${buf.size}")
            val gotCrc = ((buf[len - 2].toInt() and 0xFF) shl 8) or (buf[len - 1].toInt() and 0xFF)
            val calc = DkCrypto.crc16(buf, len - DkProtocol.CRC_LEN)
            if (gotCrc != calc)
                throw DkFrameException("CRC mismatch got=${"%04x".format(gotCrc)} calc=${"%04x".format(calc)}")
            val cmdId = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val instType = buf[6].toInt() and 0xFF
            val body = buf.copyOfRange(DkProtocol.HEADER_LEN, len - DkProtocol.CRC_LEN)
            return DkFrame(cmdId, instType, body)
        }
    }
}

class DkFrameException(msg: String) : Exception(msg)

/**
 * BasePayload = nSeq(2 BE) || mTimeStamp(4 BE) || rest.
 * Prepended to every DK payload (inside GCM for encrypted frames). Nonce
 * uniqueness for the static-IV GCM comes from these changing per frame.
 */
object DkPayload {

    private val seq = AtomicInteger(0)

    /** Monotone per-connection sequence (short); wraps 0x7FFF -> 1. Reset on new session. */
    fun nextSeq(): Int {
        while (true) {
            val cur = seq.get()
            val next = if (cur >= 0x7FFF) 1 else cur + 1
            if (seq.compareAndSet(cur, next)) return next
        }
    }

    fun resetSeq() = seq.set(0)

    fun timestamp(): Int = (System.currentTimeMillis() / 1000L).toInt()

    /** Build nSeq(2) || ts(4) || tail. Pass nSeq=0 for ACK frames. */
    fun wrap(nSeq: Int, ts: Int, tail: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(6 + tail.size).order(ByteOrder.BIG_ENDIAN)
        bb.putShort((nSeq and 0xFFFF).toShort())
        bb.putInt(ts)
        bb.put(tail)
        return bb.array()
    }

    /** Split a decrypted/plaintext payload into (nSeq, ts, tail). */
    fun unwrap(payload: ByteArray): Triple<Int, Int, ByteArray> {
        require(payload.size >= 6) { "payload too short" }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val nSeq = bb.short.toInt() and 0xFFFF
        val ts = bb.int
        val tail = ByteArray(payload.size - 6).also { bb.get(it) }
        return Triple(nSeq, ts, tail)
    }
}
