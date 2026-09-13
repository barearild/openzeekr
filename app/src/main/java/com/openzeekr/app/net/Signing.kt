package com.openzeekr.app.net

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two Zeekr signing schemes, ported byte-for-byte from the proven
 * `zeekr_ev_api` (`zeekr_hmac.generateHMAC` and `zeekr_app_sig.sign_request`).
 */
object Signing {

    // ==================== USER-CENTER (account/login) HMAC ====================
    // headers: X-HMAC-ALGORITHM/SIGNATURE/ACCESS-KEY/DIGEST + X-DATE
    // sign string = METHOD\npath\nquery\naccessKey\ngmtDate ; sig = HMAC(secret, that+"\n")

    data class HmacHeaders(
        val algorithm: String, val signature: String, val accessKey: String,
        val digest: String, val date: String,
    )

    fun usercenterHmac(method: String, url: String, accessKey: String, secretKey: String, body: ByteArray?): HmacHeaders {
        val u = URI(url)
        val date = gmtDate()
        val segs = (u.path ?: "").trim('/').split("/").filter { it.isNotEmpty() }
        val canonicalPath = if (segs.isEmpty()) "/" else "/" + segs.joinToString("/")
        val canonicalQuery = canonicalQueryLowerSorted(u.rawQuery)
        val signString = listOf(method.uppercase(), canonicalPath, canonicalQuery, accessKey, date).joinToString("\n")
        val signature = hmacB64(signString + "\n", secretKey)
        val digest = hmacB64(if (body == null) "" else String(body, Charsets.UTF_8), secretKey)
        return HmacHeaders("hmac-sha256", signature, accessKey, digest, date)
    }

    /** query keys sorted case-insensitively, "k=v" joined by "&", values raw. */
    private fun canonicalQueryLowerSorted(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            val i = pair.indexOf('=')
            if (i >= 0) map[pair.substring(0, i)] = pair.substring(i + 1)
        }
        return map.keys.sortedBy { it.lowercase() }.joinToString("&") { "$it=${map[it]}" }
    }

    // ==================== TSP gateway (app-signed) X-SIGNATURE ====================

    private val ALLOWED = setOf(
        "x-app-id", "content-type", "x-api-signature-nonce", "x-timestamp",
        "x-api-signature-version", "x-project-id", "authorization", "accept-language",
        "x-vin", "x-device-id", "x-platform",
    )

    /**
     * X-SIGNATURE over the decorated request (zeekr_app_sig.calculate_sig), key = prod_secret.
     * @param headers all request headers (already incl. nonce/timestamp).
     */
    fun tspSignature(method: String, url: String, headers: Map<String, String>, body: ByteArray?, secret: String): String {
        val u = URI(url)

        // canonical headers: allowed only, x-vin/authorization must be non-empty; "k:v\n" sorted by k
        val hdr = headers.entries
            .map { it.key.lowercase() to it.value }
            .filter { (k, v) ->
                k in ALLOWED && !((k == "x-vin" || k == "authorization") && v.isEmpty())
            }
            .sortedBy { it.first }
            .joinToString("") { (k, v) -> "$k:$v\n" }

        // canonical query: sorted by key; value.replace(%2F->/,%3F->?,*->%2A); "k=v" joined "&"
        val query = buildString {
            val q = u.rawQuery
            if (!q.isNullOrEmpty()) {
                val map = LinkedHashMap<String, String>()
                for (pair in q.split("&")) {
                    val i = pair.indexOf('='); val k = if (i >= 0) pair.substring(0, i) else pair
                    val v = if (i >= 0) pair.substring(i + 1) else ""
                    map[k] = v
                }
                var first = true
                for (k in map.keys.sorted()) {
                    val v = (map[k] ?: "").replace("%2F", "/").replace("%3F", "?").replace("*", "%2A")
                    if (!first) append("&"); first = false
                    append("$k=$v")
                }
            }
        }

        // body hash: base64(MD5(canonical sorted-key JSON)) when content-type is json
        val ct = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: ""
        var bodyHash = ""
        if (ct.contains("application/json", true) && body != null && body.isNotEmpty()) {
            runCatching {
                val canonical = canonicalizeJson(String(body, Charsets.UTF_8))
                val md5 = MessageDigest.getInstance("MD5").digest(canonical.toByteArray(Charsets.UTF_8))
                bodyHash = Base64.encodeToString(md5, Base64.NO_WRAP)
            }
        }

        val base = buildString {
            if (hdr.isNotEmpty()) append(hdr)
            if (query.isNotEmpty()) { append(query); append("\n") }
            if (bodyHash.isNotEmpty()) { append(bodyHash); append("\n") }
            append(method.uppercase()); append("\n")
            append((u.path ?: "").trimEnd())
        }
        return hmacB64(base, secret)
    }

    /**
     * Recursively sort object keys, compact (Gson-sorted-keys equivalent).
     *
     * Public because the DK/TSP gateway computes the body MD5 over the body
     * **as received** (not re-sorted) — so the client must SEND this exact
     * canonical form, or verification fails with 079025. [SignInterceptor] uses
     * this to rewrite the outgoing body so sent-bytes == signed-bytes.
     */
    fun canonicalJson(text: String): String = canonicalizeJson(text)

    private fun canonicalizeJson(text: String): String {
        val el = Json.parseToJsonElement(text)
        return sortEl(el).toString()
    }

    private fun sortEl(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject { el.keys.sorted().forEach { put(it, sortEl(el.getValue(it))) } }
        is JsonArray -> buildJsonArray { el.forEach { add(sortEl(it)) } }
        is JsonPrimitive -> el
    }

    // ==================== shared ====================

    private fun hmacB64(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun gmtDate(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(java.util.Date())
    }
}
