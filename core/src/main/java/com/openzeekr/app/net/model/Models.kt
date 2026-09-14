package com.openzeekr.app.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Generic TSP envelope. `data` is left as JsonElement-free Unit-ish via generics at call sites. */
@Serializable
data class BaseResponse<T>(
    val code: String? = null,
    val message: String? = null,
    val success: Boolean = false,
    val sessionId: String? = null,
    val data: T? = null,
)

// ------------------------------------------------------------------ control

/** Mirrors ECARX `OperationScheduling`. */
@Serializable
data class OperationScheduling(
    val duration: Int? = null,
    val scheduledTime: String? = null,
)

/** Mirrors ECARX `ServiceParameter` (key/value pair inside a command). */
@Serializable
data class ServiceParameter(
    val key: String,
    val value: String,
)

/**
 * Body of `POST /ms-remote-control/v1.0/remoteControl/control`.
 *
 * Byte-for-byte the stock `RemoteControlRequest` (smali): exactly three fields —
 * `command`, `serviceId`, and a nested `setting`. There is NO top-level
 * `serviceParameters`, `userId` or `timestamp`; the parameters live inside
 * `setting`. Getting this wrong makes the gateway accept the request (HTTP 200)
 * but the vehicle fail to execute it (code 037005 "execution failed").
 */
@Serializable
data class RemoteControlRequest(
    val command: String,
    val serviceId: String,
    val setting: RemoteControlSetting,
)

/** Stock `RemoteControlSetting`: the parameter bag carried inside a control request.
 *  `serviceParameters` has no default so it is always emitted (even when empty), and
 *  `operationScheduling` is omitted when null (encodeDefaults = false). */
@Serializable
data class RemoteControlSetting(
    val serviceParameters: List<ServiceParameter>,
    val operationScheduling: OperationScheduling? = null,
)

@Serializable
data class RemoteControlResponse(
    val serviceId: String? = null,
    val operationId: String? = null,
    val status: String? = null,
)

// ------------------------------------------------------------------ auth

@Serializable
data class LoginRequest(
    val account: String,
    /** RSA-encrypted with passwordPublicKey (see AuthRepository). */
    val password: String,
    val accountType: String = "email",
    val regionCode: String = "EU",
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val token: String? = null,
    val userId: String? = null,
    val refreshToken: String? = null,
) {
    val bearer: String? get() = accessToken ?: token
}

// ------------------------------------------------------------------ sentry / sentinel

@Serializable
data class SentryVideoDetail(
    val id: Long? = null,
    val alarmVin: String? = null,
    val alarmTime: Long? = null,
    val alarmLevel: Int? = null,
    val alarmStatus: Int? = null,
    val alarmImageUrl: String? = null,
    val alarmVideoUrl: String? = null,
    val alarmVideoLength: Long? = null,
    val alarmVideoDuration: Long? = null,
)

@Serializable
data class SentryVideoResp(val items: List<SentryVideoDetail> = emptyList())

@Serializable
data class SentryLiveTokenReq(val roomId: String, val userId: String)

@Serializable
data class SentryLiveTokenResp(
    val accessToken: String? = null,
    val expireAt: Int? = null,
    val issuedAt: Int? = null,
    val joinRoomId: String? = null,
    val joinUserId: String? = null,
)

@Serializable
data class SentryLaunchLiveResp(
    val appId: String? = null,
    val roomId: String? = null,
    val liveDuration: Int? = null,
)

@Serializable
data class SentryUploadReq(val ids: List<Long>)

// -------------------------------------------------------------- garage / identity
/** Body for renaming the car (POST modify-vehicle). */
@Serializable
data class ModifyVehicleRequest(
    val id: String? = null,
    val vehNickname: String,
    val vehiclePlateNum: String? = null,
)

/** Model/colour/render/nickname extracted from the vehicle-list. */
data class VehicleInfo(
    val model: String?,
    val colorName: String?,
    val nickName: String?,
    val photoUrl: String?,
    val vehicleId: String?,
)

/** Tolerant parse of the (shape-varying) vehicle-list `data`. */
object VehicleGarage {
    fun parse(data: JsonElement?): VehicleInfo? {
        val v = firstVehicle(data) ?: return null
        fun s(k: String) = (v[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return VehicleInfo(
            model = s("modelName") ?: s("seriesName") ?: s("innerCode") ?: s("seriesCode"),
            colorName = s("colorName") ?: materialColor(v),
            nickName = s("nickName") ?: s("vehicleNickname") ?: s("vehNickname") ?: s("remark"),
            photoUrl = s("vehiclePhotoBig") ?: s("vehicleListImgUrl") ?: s("vehiclePhotoSmall"),
            vehicleId = s("id") ?: s("vehicleId") ?: s("relationId"),
        )
    }

    private fun firstVehicle(data: JsonElement?): JsonObject? {
        when (data) {
            is JsonArray -> return data.firstOrNull() as? JsonObject
            is JsonObject -> {
                (data["list"] as? JsonArray ?: data["records"] as? JsonArray
                    ?: data["vehicleList"] as? JsonArray)?.let { return it.firstOrNull() as? JsonObject }
                if (data.containsKey("modelName") || data.containsKey("seriesName") || data.containsKey("vin")) return data
                data.values.forEach { if (it is JsonArray) (it.firstOrNull() as? JsonObject)?.let { o -> return o } }
            }
            else -> {}
        }
        return null
    }

    private fun materialColor(v: JsonObject): String? {
        val mats = v["vehicleMaterials"] as? JsonArray ?: return null
        return mats.mapNotNull { ((it as? JsonObject)?.get("materialName") as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
    }
}

// -------------------------------------------------------------- inbox / messages
// Member message-center ("Inbox") — REST on the same app-BFF gateway, no push of
// bodies (FCM only carries a deep-link nudge). See MESSAGE_CENTER_FINDINGS.md:
//   GET  overseas-app/member/inbox           (pageNumber,pageSize,customTypeId,vin) — list
//   GET  overseas-app/member/inbox/unread    -> { unreadNum }
//   PUT  overseas-app/member/inbox/{id}      — mark one read
//   POST overseas-app/member/inbox/read-all  { customTypeId, vin } — mark all read
// Categories (customTypeId groups): VEHICLE (charging done/abnormal, alarm/abnormal
// parking, remote-control results, low battery), OTA, AFTER_SALES, ZEEKR (marketing).
// The paged wrapper shape isn't verified live, so parse tolerantly (array | {records}
// | {list} | {rows} | {data:{…}}) rather than binding a rigid schema.

/** One inbox message, flattened for the UI. */
data class InboxMessage(
    val id: String?,
    val title: String?,
    val body: String?,
    val category: String?,
    val redirectUrl: String?,
    val imageUrl: String?,
    val timeMs: Long?,
    val read: Boolean,
)

object Inbox {
    /** Tolerant parse of the (shape-varying) inbox `data` into a message list. */
    fun parse(data: JsonElement?): List<InboxMessage> =
        listNode(data).mapNotNull { (it as? JsonObject)?.let(::message) }

    /** Tolerant parse of the unread-count `data` — either { unreadNum } or a bare number. */
    fun parseUnread(data: JsonElement?): Int = when (data) {
        is JsonPrimitive -> data.contentOrNull?.toIntOrNull() ?: 0
        is JsonObject -> (listOf("unreadNum", "unread", "count", "total", "num")
            .firstNotNullOfOrNull { (data[it] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }) ?: 0
        else -> 0
    }

    private fun message(o: JsonObject): InboxMessage {
        fun s(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
        val ts = s("createTime", "sendTime", "createdTime", "time", "gmtCreate", "pushTime")
        val readFlag = when (val r = o["read"] ?: o["isRead"] ?: o["readFlag"] ?: o["hasRead"]) {
            is JsonPrimitive -> r.contentOrNull.let { it == "true" || it == "1" }
            else -> false
        }
        return InboxMessage(
            id = s("id", "messageId", "noticeId", "inboxId"),
            title = s("title", "name", "subject", "noticeTitle"),
            body = s("detail", "content", "body", "text", "summary", "noticeContent"),
            category = s("customTypeId", "category", "type", "bizType", "groupType"),
            redirectUrl = s("redirectUrl", "url", "linkUrl", "jumpUrl"),
            imageUrl = s("attachment", "imageUrl", "image", "iconUrl", "picUrl"),
            timeMs = ts?.let { it.toLongOrNull() ?: parseIso(it) },
            read = readFlag,
        )
    }

    /** Find the message array wherever it lives in the response wrapper. */
    private fun listNode(data: JsonElement?): List<JsonElement> = when (data) {
        is JsonArray -> data
        is JsonObject -> {
            val direct = (data["records"] ?: data["list"] ?: data["rows"]
                ?: data["items"] ?: data["content"]) as? JsonArray
            when {
                direct != null -> direct
                data["data"] != null && data["data"] !is JsonPrimitive -> listNode(data["data"])
                else -> data.values.firstOrNull { it is JsonArray } as? JsonArray ?: emptyList()
            }
        }
        else -> emptyList()
    }

    private fun parseIso(s: String): Long? = runCatching {
        java.time.Instant.parse(if (s.endsWith("Z") || s.contains('+')) s else s + "Z").toEpochMilli()
    }.getOrNull()
}

/** Body for POST overseas-app/member/inbox/read-all. */
@Serializable
data class MarkAllReadRequest(val customTypeId: String? = null, val vin: String? = null)

// ---------------------------------------------------------- ecarx control (System B)
// Physical-actuation commands (powered tailgate RDU_2/RDL_2, charge lids RDO/RDC) don't
// execute via the plain POST /ms-remote-control path — stock dispatches them through the
// ecarx "device-api" transport: PUT /remote-control/vehicle/telematics/{vin} with a FLAT
// body (no setting{} wrapper). Same bearer + prod_secret X-SIGNATURE signing, so our
// existing interceptors cover it. Success = code 1000 or 200 (int). See
// SYSTEM_B_TRANSPORT_FINDINGS.md.

@Serializable
data class EcarxControlRequest(
    val serviceId: String,
    val command: String,
    // NOTE: no default values here — the client Json uses encodeDefaults=false, which
    // would drop a defaulted field. Every field the gateway expects must be passed
    // explicitly by [Command.toEcarxRequest] so it is actually serialized.
    val creator: String,
    val userId: String,
    val timestamp: String,
    val serviceParameters: List<ServiceParameter>,
    /** Always emitted (stock sends {} when there's no schedule). */
    val operationScheduling: JsonObject,
)

@Serializable
data class EcarxControlResponse(
    val code: Int? = null,
    val message: String? = null,
    val success: Boolean = false,
    val sessionId: String? = null,
    val data: RemoteControlResponse? = null,
) {
    /** ecarx BaseResult success sentinel: code 1000 or 200 (or an explicit success flag). */
    val ok: Boolean get() = success || code == 1000 || code == 200
}

// -------------------------------------------------------- vehicle capabilities (per-VIN)
// GET ms-vehicle-capability/api/v1.0/vehicle/function/model/info -> List<VehicleFunctionBean>.
// Presence of a functionCode = that remote function is supported on THIS car. The stock UI
// hides unsupported buttons from this list. See VEHICLE_CAPABILITIES_FINDINGS.md.

/**
 * Supported-function flags for the current car. [known] is false when we couldn't fetch
 * the list (endpoint unverified / offline) — in that case every flag reads true so we
 * "fail open" and show all controls rather than hiding everything.
 */
data class VehicleCapabilities(val codes: Set<String>, val known: Boolean = true) {
    private fun has(vararg keys: String): Boolean =
        !known || keys.any { k -> codes.any { it.contains(k, ignoreCase = true) } }

    val frunk get() = has("ZK_remote_hood_control", "hood")
    val tailgate get() = has("C_RDU_2", "trunk")
    val chargeCover get() = has("charging_cover", "charge_cover")
    val sunroof get() = has("C_RWS_4", "sunroof")
    val windows get() = has("remote_control_window")
    val sunshade get() = has("curtain", "sunshade")
    val engineRes get() = has("C_RES")
    val rpa get() = has("RPA")
    val sentry get() = has("sentry")
    val fridge get() = has("refrigerator", "fridge")
    val fragrance get() = has("fragrance")
    val climate get() = has("climate")
    val seatHeat get() = has("seat_heating")
    val seatCool get() = has("seat_ventilation")
    val steeringHeat get() = has("steering_wheel_heating")
    val charging get() = has("V_RCS", "RCS")
    val glovebox get() = has("storageBox_codeLock", "T_ZAP", "ZAD")
    val visitor get() = has("visitor", "ZAG", "ZAS")

    companion object {
        /** Nothing fetched yet — every flag reads true (show all controls). */
        val UNKNOWN = VehicleCapabilities(emptySet(), known = false)
    }
}

object VehicleCapabilityParse {
    fun parse(data: JsonElement?): VehicleCapabilities {
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["list"] as? JsonArray ?: data["records"] as? JsonArray
                ?: data["data"] as? JsonArray ?: data.values.firstOrNull { it is JsonArray } as? JsonArray)
            else -> null
        } ?: return VehicleCapabilities.UNKNOWN
        val codes = arr.mapNotNull { ((it as? JsonObject)?.get("functionCode") as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }.toSet()
        return if (codes.isEmpty()) VehicleCapabilities.UNKNOWN else VehicleCapabilities(codes)
    }
}

// -------------------------------------------------------------- vehicle status
// GET ms-vehicle-status/api/v1.0/vehicle/status/latest?latest=false&target=new
// The car status tree. No @SerializedName in the stock beans, so JSON keys == the
// field names. Only the load-bearing fields are modelled; the Retrofit Json has
// ignoreUnknownKeys=true so the rest of the (large) tree is dropped silently.
// Statuses are enum-ish Strings; numerics (odometer/speed/temps) are Ints. See
// VEHICLE_STATUS_FINDINGS.md for the full field catalog.

@Serializable
data class VehicleStatusBean(
    val basicVehicleStatus: BasicVehicleStatusVo? = null,
    val additionalVehicleStatus: AdditionalStatusVo? = null,
    val testCall: Boolean? = null,
    /** Epoch ms of this snapshot (freshness — compare with hb rvsVehicleStatusTs). */
    val updateTime: Long? = null,
)

@Serializable
data class BasicVehicleStatusVo(
    val carMode: String? = null,
    val usageMode: String? = null,
    val engineStatus: String? = null,
    val keyStatus: String? = null,
    /** Range on the current fuel/charge (String in the stock bean). */
    val distanceToEmpty: String? = null,
    val speed: Int? = null,
    val speedUnit: String? = null,
    val position: PositionVo? = null,
)

@Serializable
data class PositionVo(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val direction: Int? = null,
    val posCanBeTrusted: Boolean? = null,
)

@Serializable
data class AdditionalStatusVo(
    val climateStatus: ClimateStatusVo? = null,
    val drivingSafetyStatus: DrivingSafetyStatusVo? = null,
    val electricVehicleStatus: ElectricStatusVo? = null,
    val maintenanceStatus: MaintenanceStatusVo? = null,
    val runningStatus: RunningStatusVo? = null,
)

/** Lock / doors / trunk. */
@Serializable
data class DrivingSafetyStatusVo(
    val centralLockingStatus: String? = null,
    val electricParkBrakeStatus: String? = null,
    val doorLockStatusDriver: String? = null,
    val doorLockStatusDriverRear: String? = null,
    val doorLockStatusPassenger: String? = null,
    val doorLockStatusPassengerRear: String? = null,
    val doorOpenStatusDriver: String? = null,
    val doorOpenStatusDriverRear: String? = null,
    val doorOpenStatusPassenger: String? = null,
    val doorOpenStatusPassengerRear: String? = null,
    val engineHoodOpenStatus: String? = null,
    val trunkOpenStatus: String? = null,
    val trunkLockStatus: String? = null,
)

/** Climate + windows + sunroof. */
@Serializable
data class ClimateStatusVo(
    val interiorTemp: String? = null,
    val exteriorTemp: String? = null,
    val preClimateActive: Boolean? = null,
    val winStatusDriver: String? = null,
    val winStatusDriverRear: String? = null,
    val winStatusPassenger: String? = null,
    val winStatusPassengerRear: String? = null,
    val sunroofOpenStatus: String? = null,
)

/** Battery SOC / range / charging. */
@Serializable
data class ElectricStatusVo(
    /** State of charge, % (String in the stock bean; blank on some cars — use [chargeLevel]). */
    val stateOfCharge: String? = null,
    val chargeLevel: String? = null,
    /** UNRELIABLE on this car (reports false while actually charging) — derive from [chargeIAct]·[chargeUAct]. */
    val isCharging: Boolean? = null,
    val isPluggedIn: Boolean? = null,
    /** Charger state enum (authoritative). Charging = {2,15,24,28,30}; 5=preheat, 6=scheduled, 4/26=complete. */
    val chargerState: String? = null,
    /** AC cable connection (1/2/3 = connected, 0 = disconnected, 8=init, 9=fail, 10=partial). */
    val statusOfChargerConnection: String? = null,
    /** DC connection (1/2/3 = connected). */
    val dcDcConnectStatus: String? = null,
    /** Charge-port lid state: "1" = open, else closed (AC / DC flaps). */
    val chargeLidAcStatus: String? = null,
    val chargeLidDcAcStatus: String? = null,
    /** Live AC charge current (A) and voltage (V); their product is the real charge power. */
    val chargeIAct: String? = null,
    val chargeUAct: String? = null,
    /** EV range on battery only. */
    val distanceToEmptyOnBatteryOnly: String? = null,
) {
    /** Real charge power in watts from live current×voltage, or null if unavailable. */
    val chargePowerW: Double?
        get() {
            val a = chargeIAct?.toDoubleOrNull() ?: return null
            val v = chargeUAct?.toDoubleOrNull() ?: return null
            return a * v
        }

    /** Actively charging — by chargerState enum (isCharging is dead), power as fallback. */
    val chargingActive: Boolean
        get() {
            val cs = chargerState?.toIntOrNull()
            return (cs != null && cs in CHARGING_STATES) || (chargePowerW ?: 0.0) > CHARGE_POWER_ON_W
        }

    /** Cable plugged in (charging or not). conn/dc in {1,2,3} = connected (isPluggedIn is dead). */
    val pluggedIn: Boolean
        get() {
            if (chargingActive) return true
            val c = statusOfChargerConnection?.toIntOrNull()
            if (c != null && c in CONNECTED_STATES) return true
            val d = dcDcConnectStatus?.toIntOrNull()
            return d != null && d in CONNECTED_STATES
        }

    /** Charge-port flap open. */
    val portOpen: Boolean get() = chargeLidAcStatus == "1" || chargeLidDcAcStatus == "1"

    companion object {
        const val CHARGE_POWER_ON_W = 100.0
        private val CHARGING_STATES = setOf(2, 15, 24, 28, 30)
        private val CONNECTED_STATES = setOf(1, 2, 3)
    }
}

/** Odometer / TPMS. */
@Serializable
data class MaintenanceStatusVo(
    val odometer: Int? = null,
    val tyreStatusDriver: String? = null,
    val tyreStatusDriverRear: String? = null,
    val tyreStatusPassenger: String? = null,
    val tyreStatusPassengerRear: String? = null,
)

@Serializable
data class RunningStatusVo(
    val fuelLevel: String? = null,
    val fuelLevelPct: Int? = null,
)

/**
 * Tolerant mapper: the real status tree is huge and its field *types* vary
 * (statuses may be strings or enum-ordinal numbers, flags may be bool/0-1/"true"),
 * so binding `data` to a rigid @Serializable schema throws "unexpected JSON" on any
 * surprise. Instead we take the raw [JsonObject] and read each field defensively —
 * a wrong shape yields null, never a crash. Only the surfaced fields are extracted.
 */
object VehicleStatus {
    fun parse(root: JsonObject?): VehicleStatusBean {
        if (root == null) return VehicleStatusBean()
        val basic = root.obj("basicVehicleStatus")
        val add = root.obj("additionalVehicleStatus")
        return VehicleStatusBean(
            basicVehicleStatus = basic?.let { b ->
                BasicVehicleStatusVo(
                    carMode = b.str("carMode"),
                    usageMode = b.str("usageMode"),
                    engineStatus = b.str("engineStatus"),
                    keyStatus = b.str("keyStatus"),
                    distanceToEmpty = b.str("distanceToEmpty"),
                    speed = b.intOf("speed"),
                    speedUnit = b.str("speedUnit"),
                    position = b.obj("position")?.let { p ->
                        PositionVo(
                            latitude = p.dblOf("latitude"),
                            longitude = p.dblOf("longitude"),
                            altitude = p.dblOf("altitude"),
                            direction = p.intOf("direction"),
                            posCanBeTrusted = p.boolOf("posCanBeTrusted"),
                        )
                    },
                )
            },
            additionalVehicleStatus = add?.let { a ->
                AdditionalStatusVo(
                    climateStatus = a.obj("climateStatus")?.let { c ->
                        ClimateStatusVo(
                            interiorTemp = c.str("interiorTemp"),
                            exteriorTemp = c.str("exteriorTemp"),
                            preClimateActive = c.boolOf("preClimateActive"),
                            winStatusDriver = c.str("winStatusDriver"),
                            winStatusDriverRear = c.str("winStatusDriverRear"),
                            winStatusPassenger = c.str("winStatusPassenger"),
                            winStatusPassengerRear = c.str("winStatusPassengerRear"),
                            sunroofOpenStatus = c.str("sunroofOpenStatus"),
                        )
                    },
                    drivingSafetyStatus = a.obj("drivingSafetyStatus")?.let { d ->
                        DrivingSafetyStatusVo(
                            centralLockingStatus = d.str("centralLockingStatus"),
                            electricParkBrakeStatus = d.str("electricParkBrakeStatus"),
                            doorLockStatusDriver = d.str("doorLockStatusDriver"),
                            doorLockStatusDriverRear = d.str("doorLockStatusDriverRear"),
                            doorLockStatusPassenger = d.str("doorLockStatusPassenger"),
                            doorLockStatusPassengerRear = d.str("doorLockStatusPassengerRear"),
                            doorOpenStatusDriver = d.str("doorOpenStatusDriver"),
                            doorOpenStatusDriverRear = d.str("doorOpenStatusDriverRear"),
                            doorOpenStatusPassenger = d.str("doorOpenStatusPassenger"),
                            doorOpenStatusPassengerRear = d.str("doorOpenStatusPassengerRear"),
                            engineHoodOpenStatus = d.str("engineHoodOpenStatus"),
                            trunkOpenStatus = d.str("trunkOpenStatus"),
                            trunkLockStatus = d.str("trunkLockStatus"),
                        )
                    },
                    electricVehicleStatus = a.obj("electricVehicleStatus")?.let { e ->
                        ElectricStatusVo(
                            stateOfCharge = e.str("stateOfCharge"),
                            chargeLevel = e.str("chargeLevel"),
                            isCharging = e.boolOf("isCharging"),
                            isPluggedIn = e.boolOf("isPluggedIn"),
                            chargerState = e.str("chargerState"),
                            statusOfChargerConnection = e.str("statusOfChargerConnection"),
                            dcDcConnectStatus = e.str("dcDcConnectStatus"),
                            chargeLidAcStatus = e.str("chargeLidAcStatus"),
                            chargeLidDcAcStatus = e.str("chargeLidDcAcStatus"),
                            chargeIAct = e.str("chargeIAct"),
                            chargeUAct = e.str("chargeUAct"),
                            distanceToEmptyOnBatteryOnly = e.str("distanceToEmptyOnBatteryOnly"),
                        )
                    },
                    maintenanceStatus = a.obj("maintenanceStatus")?.let { m ->
                        MaintenanceStatusVo(
                            odometer = m.intOf("odometer"),
                            tyreStatusDriver = m.str("tyreStatusDriver"),
                            tyreStatusDriverRear = m.str("tyreStatusDriverRear"),
                            tyreStatusPassenger = m.str("tyreStatusPassenger"),
                            tyreStatusPassengerRear = m.str("tyreStatusPassengerRear"),
                        )
                    },
                    runningStatus = a.obj("runningStatus")?.let { r ->
                        RunningStatusVo(
                            fuelLevel = r.str("fuelLevel"),
                            fuelLevelPct = r.intOf("fuelLevelPct"),
                        )
                    },
                )
            },
            testCall = root.boolOf("testCall"),
            updateTime = root.str("updateTime")?.toLongOrNull(),
        )
    }

    /**
     * A PII-safe outline of the JSON: key names only (values omitted), recursing up
     * to [depth] levels into child objects. Never emits VIN/GPS/SOC/etc. — just the
     * shape — so mapping gaps (e.g. a status nested where we expected it flat) can be
     * diagnosed from the on-device debug log.
     */
    fun keyTree(root: JsonObject, depth: Int = 2): String =
        root.entries.joinToString(", ") { (k, v) ->
            if (v is JsonObject && depth > 0) "$k{${keyTree(v, depth - 1)}}" else k
        }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.intOf(key: String): Int? =
        str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    private fun JsonObject.dblOf(key: String): Double? = str(key)?.toDoubleOrNull()
    private fun JsonObject.boolOf(key: String): Boolean? = str(key)?.let {
        when (it.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    }
}
