package com.openzeekr.app.net

import android.util.Base64
import okhttp3.HttpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 AK/SK signer for the overseas-app gateway (gateway-pub-azure.zeekr.eu),
 * which the message inbox lives behind. This is a SEPARATE scheme from the TSP
 * X-SIGNATURE(prod_secret) — see OVERSEAS_APP_AUTH_FINDINGS.md.
 *
 * Headers the gateway requires: `X-HMAC-ALGORITHM: hmac-sha256`, `X-HMAC-ACCESS-KEY`,
 * `X-HMAC-SIGNATURE`, `X-HMAC-DIGEST`, `X-DATE`.
 *
 * Signature string-to-sign (each part followed by `\n`, trailing `\n` included):
 *   METHOD ‖ PATH ‖ SORTED_QUERY ‖ ACCESS_KEY ‖ X-DATE
 * where PATH is the leading-slash path (no trailing), SORTED_QUERY is the query params
 * sorted case-insensitively ascending and rebuilt `k=v&k2=v2` (empty when none), and
 * X-DATE is the same GMT date string sent in the X-DATE header (must match to the second).
 *   X-HMAC-SIGNATURE = base64NoWrap(HmacSHA256(stringToSign, secret))
 *   X-HMAC-DIGEST    = base64NoWrap(HmacSHA256(requestBody, secret))   // body "" on GET
 */
object OverseasSign {

    const val ALGORITHM = "hmac-sha256"

    /**
     * X-DATE header value, e.g. "Monday, 14 Sep 2026 09:24:56 GMT". The stock overseas-app
     * interceptor (ph/f) formats the FULL weekday name ("EEEE" → "Monday") — see
     * OVERSEAS_APP_AUTH_FINDINGS.md. The signature is computed over this exact string, so it
     * only has to be self-consistent with the X-DATE header; we match stock's format anyway.
     */
    fun dateHeader(now: Date = Date()): String =
        SimpleDateFormat("EEEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(now)

    /** Case-insensitively-sorted `k=v&k2=v2` query string for [url] (empty when no query). */
    fun sortedQuery(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        val pairs = ArrayList<Pair<String, String>>(url.querySize)
        for (i in 0 until url.querySize) pairs.add(url.queryParameterName(i) to (url.queryParameterValue(i) ?: ""))
        return pairs.sortedBy { it.first.lowercase() }
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    /** X-HMAC-SIGNATURE for the request. */
    fun signature(method: String, path: String, sortedQuery: String, accessKey: String, xDate: String, secret: String): String {
        val stringToSign = "$method\n$path\n$sortedQuery\n$accessKey\n$xDate\n"
        return hmac(stringToSign.toByteArray(Charsets.UTF_8), secret)
    }

    /** X-HMAC-DIGEST for the request body (empty bytes on a bodyless request). */
    fun digest(body: ByteArray, secret: String): String = hmac(body, secret)

    private fun hmac(data: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP)
    }
}
