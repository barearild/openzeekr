package com.openzeekr.app.net

import android.util.Base64
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Faithful port of the app's `com.baselinelibrary.sign.SignUtil.sign()`, matching
 * the working Python `sign.py` byte-for-byte.
 *
 * Base string (5 lines):
 *   getHeaders(headers) + "\n" +   // "x-api*" headers, lowercased "name:value", sorted
 *   getParam(query)     + "\n" +   // query sorted by key, k=v joined by '&'
 *   getMD5(body)        + "\n" +   // "" if empty else hex MD5 of UTF-8 body
 *   METHOD              + "\n" +
 *   path                            // path only, no scheme/host/query
 *
 * X-TIMESTAMP is sent as a plain header and is NOT part of the hashed string.
 */
object Signing {

    fun headersToSign(headers: Map<String, String>): String =
        headers.entries
            .filter { it.key.lowercase().startsWith("x-api") }
            .map { "${it.key.lowercase()}:${it.value}" }
            .sorted()
            .joinToString("\n")

    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
            .replace("%2C", "%2C")

    fun paramToSign(query: Map<String, String>): String {
        if (query.isEmpty()) return ""
        return query.keys.sorted().joinToString("&") { k -> "$k=${enc(query.getValue(k))}" }
    }

    fun md5Hex(body: ByteArray?): String {
        if (body == null || body.isEmpty()) return ""
        val digest = MessageDigest.getInstance("MD5").digest(body)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun pathOf(url: String): String = (URI(url).path ?: "/").ifEmpty { "/" }

    fun buildStringToSign(
        method: String,
        url: String,
        headers: Map<String, String>,
        query: Map<String, String>,
        body: ByteArray?,
    ): String = buildString {
        append(headersToSign(headers)); append('\n')
        append(paramToSign(query)); append('\n')
        append(md5Hex(body)); append('\n')
        append(method.uppercase()); append('\n')
        append(pathOf(url))
    }

    /** Returns X-SIGNATURE (base64) for the given base string and secret. */
    fun sign(stringToSign: String, secret: String, algo: String): String {
        val macAlgo = if (algo.equals("sha256", true)) "HmacSHA256" else "HmacSHA1"
        val mac = Mac.getInstance(macAlgo)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), macAlgo))
        val raw = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
