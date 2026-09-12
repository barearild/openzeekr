package com.openzeekr.app

import android.content.Context
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkLockController
import com.openzeekr.app.ble.ProximityController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.config.ConfigStore
import com.openzeekr.app.net.ApiClient
import com.openzeekr.app.remote.AuthRepository
import com.openzeekr.app.remote.RemoteControlRepository
import com.openzeekr.app.remote.SentryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Tiny manual DI container — one instance held by [App]. */
class Deps(context: Context) {
    private val appCtx = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val config: ConfigStore = ConfigStore.get(context)
    val apiClient: ApiClient = ApiClient.get(config)

    val auth = AuthRepository(config, apiClient)
    val control = RemoteControlRepository(config, apiClient)
    val sentry = SentryRepository(config, apiClient)

    val ble: DkBleManager = DkBleManager.get(context)
    val lock = DkLockController(ble.session)
    val rpa = RpaController(ble.session, appScope)
    val proximity = ProximityController(appCtx, config, lock, appScope)

    /** Call after the base URL / sign algo changes so the HTTP client rebuilds. */
    fun onEndpointChanged() = apiClient.rebuild()
}
