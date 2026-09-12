package com.openzeekr.app.net

import com.openzeekr.app.config.ConfigStore
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Adds the device/account headers the TSP gateway expects, then computes
 * X-SIGNATURE / X-TIMESTAMP over the final request. Order matters: header
 * injection must run BEFORE the signer so the x-api* headers are part of the
 * signed base string.
 */
class HeaderInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = store.current()
        val b = chain.request().newBuilder()

        fun put(name: String, value: String) { if (value.isNotBlank()) b.header(name, value) }

        // x-api* headers ARE included in the signature base string.
        put("X-api-key", cfg.hmacAccessKey)
        put("X-api-signature-version", cfg.sigVersion)

        // Plain, server-logged device/account headers (not signed unless x-api*).
        put("X-APP-ID", cfg.appId)
        put("X-CLIENT-ID", cfg.clientId)
        put("X-DEVICE-IDENTIFIER", cfg.deviceIdentifier)
        put("X-DEVICE-MODEL", cfg.deviceModel)
        put("X-DEVICE-BRAND", cfg.deviceBrand)
        put("X-DEVICE-TYPE", "APP")
        put("X-AGENT-TYPE", cfg.agentType)
        put("X-AGENT-VERSION", cfg.agentVersion)
        put("X-ENV-TYPE", cfg.envType)
        put("X-VERSION", cfg.appVersion)
        if (cfg.accessToken.isNotBlank()) {
            b.header("Authorization", cfg.accessToken)
            b.header("accessToken", cfg.accessToken)
            b.header("token", cfg.accessToken)
        }
        if (cfg.vin.isNotBlank()) b.header("X-VIN", cfg.vin)

        return chain.proceed(b.build())
    }
}

/** Computes X-SIGNATURE + X-TIMESTAMP over the (already header-decorated) request. */
class SignInterceptor(private val store: ConfigStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = store.current()
        val req = chain.request()

        val headers: Map<String, String> = req.headers.names().associateWith { req.header(it) ?: "" }
        val query: Map<String, String> = req.url.queryParameterNames.associateWith {
            req.url.queryParameter(it) ?: ""
        }
        val bodyBytes: ByteArray? = req.body?.let { body ->
            Buffer().use { buf -> body.writeTo(buf); buf.readByteArray() }
        }

        val sts = Signing.buildStringToSign(req.method, req.url.toString(), headers, query, bodyBytes)
        val signature = Signing.sign(sts, cfg.signSecret, cfg.signAlgo)
        val ts = System.currentTimeMillis().toString()

        val signed = req.newBuilder()
            .header("X-SIGNATURE", signature)
            .header("X-TIMESTAMP", ts)
            .build()
        return chain.proceed(signed)
    }
}
