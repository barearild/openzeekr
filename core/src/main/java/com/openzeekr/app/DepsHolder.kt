package com.openzeekr.app

/**
 * Implemented by the Application (phone [App] and the Wear app) so components in
 * :core — e.g. [com.openzeekr.app.ble.ProximityService] — can reach the shared
 * [Deps] without depending on a specific app module's Application class.
 */
interface DepsHolder {
    val deps: Deps
}
