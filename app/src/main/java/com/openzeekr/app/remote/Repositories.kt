package com.openzeekr.app.remote

import android.util.Base64
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.net.model.LoginRequest
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.SentryLiveTokenReq
import com.openzeekr.app.net.model.SentryUploadReq
import com.openzeekr.app.net.model.SentryVideoDetail
import com.openzeekr.app.net.model.ServiceParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Thin result wrapper so the UI can show ok/error uniformly. */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>
    data class Err(val message: String) : CallResult<Nothing>
}

private inline fun <T> guarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(it.message ?: it.javaClass.simpleName) }

class AuthRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** RSA-encrypt the password with the configured public key, then log in.
     *  On success the bearer token is written back into the config. */
    suspend fun login(): CallResult<String> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.email.isNotBlank() && cfg.password.isNotBlank()) { "email/password not configured" }
            val encPw = encryptPassword(cfg.password, cfg.passwordPublicKey)
            val resp = client.api.login(LoginRequest(account = cfg.email, password = encPw))
            val bearer = resp.data?.bearer ?: error(resp.message ?: "login failed (code=${resp.code})")
            store.update { it.copy(accessToken = bearer) }
            bearer
        }
    }

    private fun encryptPassword(password: String, pubKeyB64: String): String {
        if (pubKeyB64.isBlank()) return password // no key configured -> send as-is (dev)
        val keyBytes = Base64.decode(pubKeyB64, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}

class RemoteControlRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** Fire a catalog command (all "remote API functions" flow through here). */
    suspend fun send(cmd: Command, extraParams: List<ServiceParameter> = emptyList()): CallResult<RemoteControlResponse> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                require(cfg.vin.isNotBlank()) { "VIN not configured" }
                val body = cmd.toRequest(vinUserId = cfg.deviceIdentifier, extraParams = extraParams)
                val resp = client.api.sendControl(cfg.vin, body)
                resp.data ?: error(resp.message ?: "command failed (code=${resp.code})")
            }
        }

    suspend fun status(): CallResult<Map<String, String>> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            client.api.vehicleStatus(cfg.vin).data ?: emptyMap()
        }
    }
}

class SentryRepository(private val store: ConfigStore, private val client: ApiClient) {

    suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                val params = mapOf(
                    "alarmVin" to cfg.vin,
                    "alarmStartTime" to startMs.toString(),
                    "alarmEndTime" to endMs.toString(),
                    "pageNo" to "1",
                    "pageSize" to "999",
                )
                client.api.sentryEvents(params).data?.items ?: emptyList()
            }
        }

    /** Ask the car to upload specific event clips to the cloud first. */
    suspend fun requestUpload(ids: List<Long>): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { client.api.sentryRequestUpload(SentryUploadReq(ids)); Unit }
    }

    /** Obtain RTC join params for a live view. Rendering still needs the RTC
     *  provider SDK behind the returned appId (not yet identified). */
    suspend fun liveToken(roomId: String): CallResult<String> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            val tok = client.api.sentryLiveToken(SentryLiveTokenReq(roomId, cfg.deviceIdentifier)).data
            client.api.sentryLaunchLive(cfg.vin)
            tok?.accessToken ?: error("no live token")
        }
    }
}
