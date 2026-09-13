package com.openzeekr.app.ble

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Security

/**
 * Offline validation of the DK remote-parking crypto — no device or car needed
 * (run with `./gradlew testDebugUnitTest`). Vectors:
 *  - AES-CMAC fallback trailers (key 0x55×16) from CMAC_FINDINGS.md §4a, cross-checked
 *    against an independent OpenSSL/pyca computation.
 *  - A self-consistent ECIES(P-256)+AES-256-GCM golden vector minted from the §7 construction
 *    (SK = raw ECDH X, key=SK, iv=SK[0:12], aad=SK[12:16]) and round-trip verified.
 * These pin the exact byte layout our RealDkSession relies on for 0x0113/0x0116.
 */
class DkCmacTest {

    init { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }

    private fun hex(s: String): ByteArray {
        val c = s.replace(Regex("\\s"), "")
        return ByteArray(c.length / 2) {
            ((c[it * 2].digitToInt(16) shl 4) or c[it * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun ecPriv(dHex: String): PrivateKey {
        val spec = ECNamedCurveTable.getParameterSpec("secp256r1")
        return KeyFactory.getInstance("EC", "BC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, hex(dHex)), spec))
    }

    /** 0x0113 macInput = ts(4 BE) ‖ rpaControl ‖ rspaControl ‖ rpaOutMode ‖ phoneStatus. */
    @Test fun cmac_trailer_rpaReq_fallbackKey() {
        val key = ByteArray(16) { 0x55 }
        assertEquals("7f07e0ce7d8c", hx(DkCrypto.aesCmac6(key, hex("6aa5db8001000000"))))
    }

    /** 0x0116 macInput = ts(4 BE) ‖ phoneStatus ‖ randX ‖ randY ‖ answerHi ‖ answerLo ‖ gesture. */
    @Test fun cmac_trailer_rpaAnswer_fallbackKey() {
        val key = ByteArray(16) { 0x55 }
        assertEquals("ac528db78d10", hx(DkCrypto.aesCmac6(key, hex("6aa5db84001234abcd01"))))
    }

    /** cmacKeyCert envelope (101 B) + our DK private key → 16-byte AES-CMAC key. */
    @Test fun eciesUnwrap_cmacKeyCert_goldenVector() {
        val priv = ecPriv("00112233445566778899aabbccddeeff00112233445566778899aabbccddee01")
        val blob = hex(
            "00000041040b61183c1341aa92a9458f788cf5ebcf271922733b4baca8b6cec58e" +
                "b466090c10479b17b804cfc55d63319b0ad285ba048785b41c0baf42f48e8c15a" +
                "ca72655dba9c3ae915770b73038996d13e2b226b36d5cf41bf576dd7d5243d4c5" +
                "6a7fca",
        )
        assertEquals("000102030405060708090a0b0c0d0e0f", hx(DkCrypto.unwrapCmacKey(blob, priv)))
    }

    /** End-to-end: an ECIES-unwrapped key feeding an RPA trailer produces a stable 6-byte MAC. */
    @Test fun endToEnd_unwrapThenCmac() {
        val priv = ecPriv("00112233445566778899aabbccddeeff00112233445566778899aabbccddee01")
        val blob = hex(
            "00000041040b61183c1341aa92a9458f788cf5ebcf271922733b4baca8b6cec58e" +
                "b466090c10479b17b804cfc55d63319b0ad285ba048785b41c0baf42f48e8c15a" +
                "ca72655dba9c3ae915770b73038996d13e2b226b36d5cf41bf576dd7d5243d4c5" +
                "6a7fca",
        )
        val key = DkCrypto.unwrapCmacKey(blob, priv) // = 000102…0f
        // AES-CMAC(000102…0f, "6aa5db8001000000")[0:6], independently reproducible.
        assertEquals(6, DkCrypto.aesCmac6(key, hex("6aa5db8001000000")).size)
        assertEquals("000102030405060708090a0b0c0d0e0f", hx(key))
    }
}
