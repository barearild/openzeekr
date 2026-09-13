package com.openzeekr.app.net

import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.util.Logx
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * TSP-gateway transport (bearer login, vehicle list, DK provisioning, remote
 * control). Ported from `zeekr_ev_api` appSignedPost/appSignedGet:
 *   HeaderInterceptor -> LOGGED_IN_HEADERS + authorization + (x-vin)
 *   SignInterceptor   -> X-API-SIGNATURE-NONCE + X-TIMESTAMP + X-SIGNATURE
 * (order matters: headers first so they are part of the signed base string).
 *
 * Account/login requests use a SEPARATE user-center client (see AccountLogin)
 * with DEFAULT_HEADERS + X-HMAC-* — do NOT route those through these.
 */
class HeaderInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = store.current()
        val b = chain.request().newBuilder()

        // LOGGED_IN_HEADERS base (don't override anything a caller already set).
        // X-DEVICE-ID = app-instance UUID (like stock's ecc8e262-…), NOT the DK deviceId.
        ZeekrConst.loggedInHeaders(cfg.projectId, cfg.appInstanceId).forEach { (k, v) ->
            if (chain.request().header(k) == null) b.header(k, v)
        }
        if (cfg.accessToken.isNotBlank()) b.header("authorization", cfg.accessToken)
        // X-VIN is AES-CBC(vin_key/vin_iv)-encrypted; only send it when we can encrypt
        // it correctly (blank key -> omit rather than send a bad raw value).
        val sendVin = cfg.vin.isNotBlank() && cfg.vinKey.isNotBlank() && cfg.vinIv.isNotBlank()
        if (sendVin) b.header("x-vin", VinCrypto.encryptVin(cfg.vin, cfg.vinKey, cfg.vinIv))
        Logx.d("tsp", "${chain.request().method} ${chain.request().url.encodedPath} " +
            "auth=${if (cfg.accessToken.isNotBlank()) "yes" else "no"} x-vin=${if (sendVin) "yes" else "no"}")
        return chain.proceed(b.build())
    }
}

/**
 * Adds nonce + timestamp, then X-SIGNATURE over the decorated request (key = prod_secret).
 *
 * CRITICAL (learned from live 079025 debugging): the gateway verifies the body
 * MD5 over the body **exactly as received** — it does NOT re-sort keys. Our
 * signature hashes the sorted-canonical JSON, so we must also SEND that canonical
 * form, otherwise the two MD5s differ and verification fails. bearer_login only
 * worked by luck (its keys were already alphabetical). So for any JSON body we:
 *   1. rewrite the outgoing body to the sorted-canonical JSON,
 *   2. pin Content-Type to "application/json; charset=UTF-8" (signed + sent), and
 *   3. sign those exact bytes (with the body-MD5 included).
 */
class SignInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = store.current()
        var req = chain.request().newBuilder()
            .header("X-API-SIGNATURE-NONCE", UUID.randomUUID().toString())
            .header("X-TIMESTAMP", System.currentTimeMillis().toString())
            .build()

        // Raw bytes of the original body (if any).
        val origBytes: ByteArray? = req.body?.let { body ->
            Buffer().use { buf -> body.writeTo(buf); buf.readByteArray() }
        }
        val isJson = req.body?.contentType()?.let {
            it.type == "application" && it.subtype.contains("json", ignoreCase = true)
        } == true

        // For JSON: canonicalize (sort keys) and make THAT the body we send, with a
        // pinned Content-Type, so sent-bytes == signed-bytes and the header we sign
        // is the header we send.
        val signedBody: ByteArray?
        if (isJson && origBytes != null && origBytes.isNotEmpty()) {
            val canonical = runCatching { Signing.canonicalJson(String(origBytes, Charsets.UTF_8)) }
                .getOrElse { String(origBytes, Charsets.UTF_8) }
            signedBody = canonical.toByteArray(Charsets.UTF_8)
            req = req.newBuilder()
                .method(req.method, signedBody.toRequestBody(JSON_CT))
                .header("Content-Type", "application/json; charset=UTF-8")
                .build()
        } else {
            signedBody = origBytes
        }

        val headers: Map<String, String> = req.headers.names().associateWith { req.header(it) ?: "" }
        val signature = Signing.tspSignature(
            req.method, req.url.toString(), headers, signedBody, cfg.signSecret,
        )
        return chain.proceed(req.newBuilder().header("X-SIGNATURE", signature).build())
    }

    companion object {
        private val JSON_CT = "application/json; charset=UTF-8".toMediaType()
    }
}
