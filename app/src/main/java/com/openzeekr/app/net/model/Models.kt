package com.openzeekr.app.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
