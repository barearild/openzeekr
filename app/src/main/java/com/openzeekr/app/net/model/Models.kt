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
 * Mirrors ECARX `RemoteControlRequest` — the body of
 * `PUT /remote-control/vehicle/telematics/{vin}`.
 */
@Serializable
data class RemoteControlRequest(
    val serviceId: String,
    val command: String,
    val serviceParameters: List<ServiceParameter> = emptyList(),
    val operationScheduling: OperationScheduling? = null,
    val userId: String? = null,
    val creator: String? = null,
    val engStrtType: String? = null,
    val requestVersion: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** Present in the schema but never populated by the stock client. */
    val pin: String? = null,
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
