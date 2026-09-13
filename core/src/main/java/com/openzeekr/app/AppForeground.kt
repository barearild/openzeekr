package com.openzeekr.app

/**
 * Process-wide foreground flag. Set by the app/wear Application from its activity
 * lifecycle callbacks; read by [com.openzeekr.app.ble.PhoneStatusProvider] to pack
 * the phone-status byte into DK/RPA frames. Lives in :core so the BLE layer can
 * read it without depending on the app module.
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
