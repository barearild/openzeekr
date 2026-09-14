package com.openzeekr.app.remote

import android.util.Base64
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.net.model.LoginRequest
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.SentryLiveTokenReq
import com.openzeekr.app.net.model.SentryUploadReq
import com.openzeekr.app.net.model.SentryVideoDetail
import com.openzeekr.app.net.model.ModifyVehicleRequest
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.net.model.VehicleGarage
import com.openzeekr.app.net.model.VehicleInfo
import com.openzeekr.app.net.model.VehicleStatus
import com.openzeekr.app.net.model.VehicleStatusBean
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

/**
 * User message for a sentry/sentinel failure. The `sentinel-monitoring-service` is
 * NOT routed on the EU TSP gateway (the gateway answers 404 / code "00A01" — verified:
 * our path & params are byte-identical to the stock app; the service is only deployed
 * behind CN/other-region gateways). Surface that plainly instead of a raw HTTP 404.
 */
const val SENTRY_REGION_UNAVAILABLE =
    "Sentry isn't available for this account's region — the sentinel-monitoring-service " +
        "isn't routed on the EU gateway. It only works on CN (or other-region) accounts."

fun sentryMessage(t: Throwable): String =
    if ((t as? retrofit2.HttpException)?.code() == 404) SENTRY_REGION_UNAVAILABLE
    else t.message ?: t.javaClass.simpleName

private inline fun <T> sentryGuarded(block: () -> T): CallResult<T> =
    runCatching { CallResult.Ok(block()) }
        .getOrElse { CallResult.Err(sentryMessage(it)) }

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

    /** Fire a catalog command. Physical-actuation ids (RDU_2/RDL_2/RDO/RDC) route through
     *  the ecarx device-api transport (System B); everything else through /ms-remote-control. */
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
                if (cmd.usesSystemB) {
                    // Flat body, PUT /remote-control/vehicle/telematics/{vin}, ecarx success sentinel.
                    val resp = client.api.ecarxControl(cfg.vin, cmd.toEcarxRequest(cfg.userId, extraParams))
                    if (!resp.ok) error(resp.message ?: "command failed (code=${resp.code})")
                    resp.data ?: RemoteControlResponse(serviceId = cmd.serviceId, status = "ok")
                } else {
                    // Body = command/serviceId/setting{serviceParameters,...}; the account is
                    // identified by the bearer token + X-VIN header, not a body field.
                    val resp = client.api.sendControl(cmd.toRequest(extraParams = extraParams))
                    resp.data ?: error(resp.message ?: "command failed (code=${resp.code})")
                }
            }
        }

    /** Per-VIN supported functions (drives button visibility). Fail-open on error. */
    suspend fun capabilities(): CallResult<com.openzeekr.app.net.model.VehicleCapabilities> = withContext(Dispatchers.IO) {
        guarded { com.openzeekr.app.net.model.VehicleCapabilityParse.parse(client.api.vehicleCapability().data) }
    }

    /**
     * Fetch the real vehicle status tree (lock/doors/SOC/range/climate/odometer/…).
     * A plain GET already returns real data; we heartbeat first (as with [send]) so
     * the cloud has us marked ONLINE and returns a fresh snapshot. VIN rides in the
     * X-VIN header; the query params (latest=false, target=new) mirror the stock app.
     */
    suspend fun status(): CallResult<VehicleStatusBean> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            require(cfg.vin.isNotBlank()) { "VIN not configured" }
            runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
            val resp = client.api.vehicleStatus()
            val obj = resp.data ?: error(resp.message ?: "status failed (code=${resp.code})")
            // PII-safe: log only the key structure (names, never values like VIN/GPS/SOC)
            // so an unexpected shape can be diagnosed from the on-device debug log.
            com.openzeekr.app.util.Logx.d("status", "keys=${VehicleStatus.keyTree(obj)}")
            // `data` is a raw JsonObject; map it tolerantly (never throws on shape).
            VehicleStatus.parse(obj)
        }
    }

    /** Garage lookup: the car's model / colour / render / nickname (best-effort). */
    suspend fun vehicleInfo(): CallResult<VehicleInfo?> = withContext(Dispatchers.IO) {
        guarded { VehicleGarage.parse(client.api.vehicleList().data) }
    }

    /** Rename the car (cloud). vehicleId is optional; the backend also keys off X-VIN. */
    suspend fun renameVehicle(name: String, vehicleId: String? = null): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { client.api.modifyVehicle(ModifyVehicleRequest(id = vehicleId, vehNickname = name)); Unit }
    }
}

/**
 * Member message-center ("Inbox"): charging done/abnormal, alarm / abnormal parking,
 * remote-control results, low battery, OTA, marketing. On-demand paged REST on the same
 * gateway — there is no push of message bodies (FCM only deep-links). Endpoints and
 * response shapes are reversed but not yet verified live, so everything is tolerant.
 */
class InboxRepository(private val store: ConfigStore, private val client: ApiClient) {

    /** One page of messages (newest first as the server returns them). */
    suspend fun messages(page: Int = 1, pageSize: Int = 30): CallResult<List<com.openzeekr.app.net.model.InboxMessage>> =
        withContext(Dispatchers.IO) {
            guarded {
                val cfg = store.current()
                runCatching { com.openzeekr.app.net.AccountLogin(store).heartbeat() }
                com.openzeekr.app.net.model.Inbox.parse(
                    client.api.inbox(INBOX, pageNumber = page, pageSize = pageSize, vin = cfg.vin.ifBlank { null }).data)
            }
        }

    /** Unread badge count. */
    suspend fun unreadCount(): CallResult<Int> = withContext(Dispatchers.IO) {
        guarded { com.openzeekr.app.net.model.Inbox.parseUnread(client.api.inboxUnread("$INBOX/unread").data) }
    }

    /** Mark a single message read. */
    suspend fun markRead(id: String): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded { client.api.inboxMarkRead("$INBOX/$id"); Unit }
    }

    /** Mark every message read. */
    suspend fun markAllRead(): CallResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val cfg = store.current()
            client.api.inboxReadAll("$INBOX/read-all", com.openzeekr.app.net.model.MarkAllReadRequest(vin = cfg.vin.ifBlank { null })); Unit
        }
    }

    private companion object {
        /**
         * The inbox lives on a SEPARATE "overseas-app" backend — an Azure zeekr.eu gateway,
         * not the TSP gateway (which 404s) and not overseas-app.lynkco.com (a marketing site
         * that returns HTML). See INBOX_HOST_FINDINGS.md.
         *
         * ⚠️ AUTH DIFFERS: this host does NOT accept the TSP X-SIGNATURE(prod_secret)+X-VIN
         * scheme our interceptors add. It needs its own header set — Authorization (bearer,
         * which we have) + app-authorization + a static App-Code token + appSecret=zeekr_tis +
         * Tmp-Tenant-Code + appId=TSP + appCode=eu-app + Client-Id + language/country, with vin
         * as a @Query (already sent). Until a dedicated app-BFF client with those headers is
         * wired, this call reaches the right host but 401s. Tracked as back-burner.
         */
        const val INBOX = "https://gateway-pub-azure.zeekr.eu/overseas-app/member/inbox"
    }
}

class SentryRepository(private val store: ConfigStore, private val client: ApiClient) {

    suspend fun events(startMs: Long, endMs: Long): CallResult<List<SentryVideoDetail>> =
        withContext(Dispatchers.IO) {
            sentryGuarded {
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
        sentryGuarded { client.api.sentryRequestUpload(SentryUploadReq(ids)); Unit }
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
                CallResult.Err(sentryMessage(e))
            }
        }

    private suspend fun videoUrlFor(id: Long, startMs: Long, endMs: Long): String? =
        (events(startMs, endMs) as? CallResult.Ok)?.value
            ?.firstOrNull { it.id == id }?.alarmVideoUrl

    /** Obtain RTC join params for a live view. Rendering still needs the RTC
     *  provider SDK behind the returned appId (not yet identified). */
    suspend fun liveToken(roomId: String): CallResult<String> = withContext(Dispatchers.IO) {
        sentryGuarded {
            val cfg = store.current()
            val tok = client.api.sentryLiveToken(SentryLiveTokenReq(roomId, cfg.deviceIdentifier)).data
            client.api.sentryLaunchLive(cfg.vin)
            tok?.accessToken ?: error("no live token")
        }
    }
}
