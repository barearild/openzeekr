package com.openzeekr.app.ble

/**
 * GATT-layer fragmentation for DK frames (the layer below [DkFrame]).
 *
 * Observed scheme (from live captures):
 *  - A frame is `FE 03 | len(2 BE) | …`. The first GATT chunk carries the frame
 *    bytes verbatim starting at `FE 03`; `len` tells the receiver the total size.
 *  - Each continuation chunk is prefixed `fragIdx(2 BE) ‖ totalFrags(2 BE)` then
 *    the next slice of frame bytes. Reassembly appends slices until `len` bytes.
 *
 * NOTE: exact per-chunk size depends on the negotiated ATT MTU; the receiver
 * reassembles by total length, so chunk sizing is not critical. Still worth a
 * btsnoop confirm on the first at-car test.
 */
class DkReassembler {
    private var buf = ByteArray(0)
    private var expected = -1

    /** Feed one GATT notification; returns a complete frame's bytes when ready, else null. */
    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        if (buf.isEmpty()) {
            // must be a frame start
            if (chunk.size >= 2 && chunk[0] == DkProtocol.MAGIC && chunk[1] == DkProtocol.VER_ZEEKR) {
                if (chunk.size < 4) { buf = chunk.copyOf(); expected = -1; return null }
                expected = ((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)
                buf = chunk.copyOf()
            } else {
                // stray continuation with no active frame — ignore
                return null
            }
        } else {
            // continuation: strip 4-byte fragIdx/total header if present
            val slice = if (chunk.size > 4) chunk.copyOfRange(4, chunk.size) else ByteArray(0)
            buf += slice
            if (expected < 0 && buf.size >= 4)
                expected = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        }
        return if (expected in 1..buf.size) {
            val frame = buf.copyOfRange(0, expected)
            buf = ByteArray(0); expected = -1
            frame
        } else null
    }

    fun reset() { buf = ByteArray(0); expected = -1 }
}

object DkFragmenter {
    /**
     * Split a full frame into GATT write chunks for [maxChunk]-byte writes.
     * First chunk = frame start (`FE 03…`); continuations get `fragIdx‖total` headers.
     */
    fun split(frame: ByteArray, maxChunk: Int): List<ByteArray> {
        if (frame.size <= maxChunk) return listOf(frame)
        val chunks = ArrayList<ByteArray>()
        chunks.add(frame.copyOfRange(0, maxChunk))
        var off = maxChunk
        val contPayload = maxChunk - 4
        require(contPayload > 0) { "MTU too small" }
        val total = ((frame.size - maxChunk) + contPayload - 1) / contPayload
        var idx = 1
        while (off < frame.size) {
            val end = minOf(off + contPayload, frame.size)
            val body = frame.copyOfRange(off, end)
            val out = ByteArray(4 + body.size)
            out[0] = ((idx ushr 8) and 0xFF).toByte(); out[1] = (idx and 0xFF).toByte()
            out[2] = ((total ushr 8) and 0xFF).toByte(); out[3] = (total and 0xFF).toByte()
            System.arraycopy(body, 0, out, 4, body.size)
            chunks.add(out)
            off = end; idx++
        }
        return chunks
    }
}
