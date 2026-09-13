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

    /**
     * Full Zeekr account login (see [com.openzeekr.app.net.AccountLogin]):
     * checkUser → loginByEmailEncrypt → user/info → tspCode → bearer_login →
     * vehicle-list. Writes accessToken + userId + vin into config.
     */
    suspend fun login(): CallResult<String> = withContext(Dispatchers.IO) {
        val r = com.openzeekr.app.net.AccountLogin(store).login()
        r.fold(
            onSuccess = { CallResult.Ok(store.current().accessToken) },
            onFailure = { CallResult.Err(it.message ?: it.javaClass.simpleName) },
        )
    }
}

class RemoteControlRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** Fire a catalog command (all "remote API functions" flow through here). */
    suspend fun send(cmd: Command, extraParams: List<ServiceParameter> = emptyList()): CallResult<RemoteControlResponse> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                require(cfg.vin.isNotBlank()) { "VIN not configured" }
                // The vehicle only executes remote commands for the account's ONLINE
                // device. Stock heartbeats app/hb continuously; refresh our online
                // status right before the command so the TSP doesn't reject execution
                // (037005 "execution failed, please try again"). Best-effort.
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                // Body = command/serviceId/setting{serviceParameters,...}; the account is
                // identified by the bearer token + X-VIN header, not a body field.
                val body = cmd.toRequest(extraParams = extraParams)
                // VIN is carried in the X-VIN header by HeaderInterceptor.
                val resp = client.api.sendControl(body)
                resp.data ?: error(resp.message ?: "command failed (code=${resp.code})")
            }
        }

    suspend fun status(): CallResult<Map<String, String>> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            // VIN is carried in the X-VIN header by HeaderInterceptor.
            client.api.vehicleStatus().data ?: emptyMap()
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

    /**
     * Get a playable clip URL for [id]: if the event already has one, return it;
     * otherwise ask the car to upload it and poll the event list (over [startMs]..
     * [endMs]) until the cloud URL appears. Returns the direct video URL to download.
     */
    suspend fun prepareDownload(id: Long, startMs: Long, endMs: Long): CallResult<String> =
        withContext(Dispatchers.IO) {
            try {
                var url = videoUrlFor(id, startMs, endMs)
                if (url == null) {
                    client.api.sentryRequestUpload(SentryUploadReq(listOf(id)))
                    var tries = 0
                    while (url == null && tries < 40) {   // ~2 min at 3s
                        kotlinx.coroutines.delay(3000); tries++
                        url = videoUrlFor(id, startMs, endMs)
                    }
                }
                url?.let { CallResult.Ok(it) } ?: CallResult.Err("clip not ready after upload (timed out)")
            } catch (e: Exception) {
                CallResult.Err(e.message ?: e.javaClass.simpleName)
            }
        }

    private suspend fun videoUrlFor(id: Long, startMs: Long, endMs: Long): String? =
        (events(startMs, endMs) as? CallResult.Ok)?.value
            ?.firstOrNull { it.id == id }?.alarmVideoUrl

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
