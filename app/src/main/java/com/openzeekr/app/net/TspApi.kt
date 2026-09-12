package com.openzeekr.app.net

import com.openzeekr.app.net.model.BaseResponse
import com.openzeekr.app.net.model.LoginRequest
import com.openzeekr.app.net.model.LoginResponse
import com.openzeekr.app.net.model.RemoteControlRequest
import com.openzeekr.app.net.model.RemoteControlResponse
import com.openzeekr.app.net.model.SentryLaunchLiveResp
import com.openzeekr.app.net.model.SentryLiveTokenReq
import com.openzeekr.app.net.model.SentryLiveTokenResp
import com.openzeekr.app.net.model.SentryUploadReq
import com.openzeekr.app.net.model.SentryVideoResp
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * The remote (cloud) API surface, reconstructed from the ECARX/Geely TSP
 * retrofit interfaces in the decompiled app. Auth + X-SIGNATURE are added by
 * the interceptors, so call sites just pass typed bodies.
 */
interface TspApi {

    // ---- account ----
    @POST("/auth/customer/login")
    suspend fun login(@Body body: LoginRequest): BaseResponse<LoginResponse>

    // ---- remote vehicle control (all serviceIds route through this one endpoint) ----
    @PUT("/remote-control/vehicle/telematics/{vin}")
    suspend fun sendControl(
        @Path("vin") vin: String,
        @Body body: RemoteControlRequest,
    ): BaseResponse<RemoteControlResponse>

    // ---- vehicle status ----
    @GET("/remote-control/vehicle/status")
    suspend fun vehicleStatus(@Query("vin") vin: String): BaseResponse<Map<String, String>>

    @GET("/remote-control/vehicle/status/soc/{vin}")
    suspend fun soc(@Path("vin") vin: String): BaseResponse<Map<String, String>>

    // ---- sentry / sentinel-monitoring-service ----
    @GET("/sentinel-monitoring-service/api/v1/alarm/event/query")
    suspend fun sentryEvents(@QueryMap params: Map<String, String>): BaseResponse<SentryVideoResp>

    @POST("/sentinel-monitoring-service/api/v1/alarm/event/launchUploadVideo")
    suspend fun sentryRequestUpload(@Body body: SentryUploadReq): BaseResponse<SentryVideoResp>

    @POST("/sentinel-monitoring-service/api/v1/alarm/live/app/getToken")
    suspend fun sentryLiveToken(@Body body: SentryLiveTokenReq): BaseResponse<SentryLiveTokenResp>

    @GET("/sentinel-monitoring-service/api/v1/alarm/live/app/launchLive")
    suspend fun sentryLaunchLive(@Query("alarmVin") vin: String): BaseResponse<SentryLaunchLiveResp>

    @GET("/sentinel-monitoring-service/api/v1/pic/list")
    suspend fun parkingSnapshots(): BaseResponse<SentryVideoResp>
}
