package com.openzeekr.app.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val chargerState: String? = null,
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

    /** True charging state: trust live power over the (buggy) isCharging flag. */
    val chargingActive: Boolean
        get() = (chargePowerW ?: 0.0) > CHARGE_POWER_ON_W || isCharging == true

    companion object {
        /** Above this many watts we consider the car actively charging (ignores noise/trickle). */
        const val CHARGE_POWER_ON_W = 100.0
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
