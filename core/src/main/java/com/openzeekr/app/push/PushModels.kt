package com.openzeekr.app.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * FCM device-token registration models for the Zeekr message-centre (mcs) backend.
 *
 * Ported byte-for-byte from the stock app's `DeviceTokenSyncRequest` /
 * `DeviceTokenDisableRequest` (com.zeekr.overseas.common.model) and verified against a live
 * capture of the stock POST (see [PushRegistrar]). Kept in :core/push (NOT Models.kt) so this
 * feature is self-contained.
 *
 * Field ORDER matters: the stock serializes exactly this order and the gateway HMAC digest is
 * computed over the body bytes as sent, so [PushRegistrar] must emit these in order.
 */
@Serializable
data class DeviceTokenSyncRequest(
    /** Message-centre "push id". Prod EU = "10008" (= the `msgAppId` header). */
    @SerialName("appId") val appId: String,
    /** The FCM registration token from FirebaseMessaging.getInstance().token. */
    @SerialName("deviceToken") val deviceToken: String,
    /** 1 = Android (stock default). */
    @SerialName("platformType") val platformType: Int = 1,
    /** The account openId (user-center `uuid`, 32-hex) — SecretsConfig.accountUuid. */
    @SerialName("receive") val receive: String,
    /** AWS SNS region the backend files the endpoint under. EU = "eu-central-1". */
    @SerialName("region") val region: String = "eu-central-1",
)

/**
 * Unregister (logout) body. The stock `DeviceTokenDisableRequest` carries only these three
 * fields (no platformType / region).
 */
@Serializable
data class DeviceTokenDisableRequest(
    @SerialName("appId") val appId: String,
    @SerialName("deviceToken") val deviceToken: String,
    @SerialName("receive") val receive: String,
)
