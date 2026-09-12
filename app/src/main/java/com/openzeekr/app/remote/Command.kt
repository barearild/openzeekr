package com.openzeekr.app.remote

import com.openzeekr.app.net.model.OperationScheduling
import com.openzeekr.app.net.model.RemoteControlRequest
import com.openzeekr.app.net.model.ServiceParameter

/** UI grouping for the command grid. */
enum class Category { DOORS, CLIMATE, WINDOWS, CHARGING, SIGNAL, SECURITY, COMFORT, SYSTEM }

/**
 * The complete cloud remote-control catalog, reconstructed from the app's
 * `*CommandCreator` factory: each entry maps a user-facing action to a TSP
 * (serviceId, command, serviceParameters[]) tuple. Sent via
 * `PUT /remote-control/vehicle/telematics/{vin}`.
 *
 * Parametric commands (temperature, SOC, charge level) accept overrides through
 * [RemoteControlRepository.send]'s `extraParams`; the defaults here are safe
 * no-op-ish values and are marked TODO where a real UI value belongs.
 */
enum class Command(
    val title: String,
    val category: Category,
    val serviceId: String,
    val command: String,
    val params: List<ServiceParameter> = emptyList(),
    val engStrtType: String? = null,
    val durationSec: Int? = null,
) {
    // ---- doors / locks ----
    UNLOCK("Unlock", Category.DOORS, "RDU_2", "start"),
    LOCK("Lock", Category.DOORS, "RDL_2", "start"),
    TRUNK_OPEN("Open Trunk", Category.DOORS, "RDU_2", "start", listOf(ServiceParameter("DOOR", "LOCK_TRUNK"))),
    TRUNK_UNLOCK("Unlock Trunk", Category.DOORS, "RDU_2", "start", listOf(ServiceParameter("DOOR", "LOCK_TRUNK"))),
    TRUNK_LOCK("Lock Trunk", Category.DOORS, "RDL_2", "start", listOf(ServiceParameter("DOOR", "LOCK_TRUNK"))),
    FRONT_TRUNK("Front Trunk (frunk)", Category.DOORS, "UFR", "start"),
    CHARGE_LID_OPEN("Open Charge Lid", Category.DOORS, "RDO", "start"),
    CHARGE_LID_CLOSE("Close Charge Lid", Category.DOORS, "RDC", "stop"),

    // ---- climate / comfort ----
    AC_ON("A/C On", Category.CLIMATE, "RCE_2", "start", listOf(ServiceParameter("RCE_CONDITIONER", "ENABLE"))),
    AC_OFF("A/C Off", Category.CLIMATE, "RCE_2", "stop", listOf(ServiceParameter("RCE_CONDITIONER", "DISABLE"))),
    CABIN_ON("Cabin Precondition On", Category.CLIMATE, "RCC_2", "start"),
    CABIN_OFF("Cabin Precondition Off", Category.CLIMATE, "RCC_2", "stop"),
    DEFROST_ON("Defrost On", Category.CLIMATE, "RCE_2", "start", listOf(ServiceParameter("RCE_HEAT", "ENABLE"))),
    DEFROST_OFF("Defrost Off", Category.CLIMATE, "RCE_2", "stop", listOf(ServiceParameter("RCE_HEAT", "DISABLE"))),
    SEAT_HEAT_ON("Seat Heat On", Category.COMFORT, "RCE_2", "start", listOf(ServiceParameter("RSH_SEAT", "SEAT1"), ServiceParameter("RSH_LEAVEL", "RSH_LEVEL_MIDDLE"))),
    SEAT_HEAT_OFF("Seat Heat Off", Category.COMFORT, "RCE_2", "stop", listOf(ServiceParameter("RSH_SEAT", "SEAT1"))),
    STEER_WHEEL_ON("Steering Wheel Heat On", Category.COMFORT, "RCE_2", "start", listOf(ServiceParameter("TARGET", "RCC_STEERING"))),
    STEER_WHEEL_OFF("Steering Wheel Heat Off", Category.COMFORT, "RCE_2", "stop", listOf(ServiceParameter("TARGET", "RCC_STEERING"))),
    FRAGRANCE_ON("Fragrance On", Category.COMFORT, "RFD", "start"),
    FRAGRANCE_OFF("Fragrance Off", Category.COMFORT, "RFD", "stop"),
    FRIDGE_ON("Fridge On", Category.COMFORT, "ZAE", "start", listOf(ServiceParameter("SWITCH", "ENABLE"))),
    FRIDGE_OFF("Fridge Off", Category.COMFORT, "ZAE", "stop", listOf(ServiceParameter("SWITCH", "DISABLE"))),

    // ---- engine / RES ----
    // RES engine/climate start: engStrtType + a duration (creator did duration*6).
    ENGINE_START("Remote Start", Category.CLIMATE, "RES", "start", engStrtType = "RES_THREE", durationSec = 900),
    ENGINE_STOP("Remote Stop", Category.CLIMATE, "RES", "stop"),

    // ---- windows / sunroof ----
    WINDOW_OPEN("Windows Open", Category.WINDOWS, "RWS_2", "start", listOf(ServiceParameter("TARGET", "WINDOW"))),
    WINDOW_CLOSE("Windows Close", Category.WINDOWS, "RWS_2", "stop", listOf(ServiceParameter("TARGET", "WINDOW"))),
    WINDOW_VENT("Windows Vent", Category.WINDOWS, "RWS_2", "start", listOf(ServiceParameter("TARGET", "WIN_VENTILATE"))),
    SUNROOF_OPEN("Sunroof Open", Category.WINDOWS, "RWS_2", "start", listOf(ServiceParameter("TARGET", "SUNROOF"))),
    SUNROOF_CLOSE("Sunroof Close", Category.WINDOWS, "RWS_2", "stop", listOf(ServiceParameter("TARGET", "SUNROOF"))),
    SUNSHADE_OPEN("Sunshade Open", Category.WINDOWS, "RWS_2", "start", listOf(ServiceParameter("TARGET", "WIN_SUNSHADE"))),
    SUNSHADE_CLOSE("Sunshade Close", Category.WINDOWS, "RWS_2", "stop", listOf(ServiceParameter("TARGET", "WIN_SUNSHADE"))),

    // ---- charging ----
    CHARGING_ON("Start Charging", Category.CHARGING, "RCS", "start", listOf(ServiceParameter("RCS_SETTING", "RCS_RESTART"))),
    CHARGING_OFF("Stop Charging", Category.CHARGING, "RCS", "stop", listOf(ServiceParameter("RCS_SETTING", "RCS_TERMINATE"))),
    // TODO: SET_CHARGE_SOC target is fixed at 80 here; wire to a slider in UI.
    SET_CHARGE_SOC("Set Charge Limit 80%", Category.CHARGING, "RCS", "start", listOf(ServiceParameter("SETTING_TYPE", "SETTING_SOC"), ServiceParameter("SETTING_SOC", "80"))),
    BATTERY_PREHEAT_ON("Battery Preheat On", Category.CHARGING, "ZAN", "start"),
    BATTERY_PREHEAT_OFF("Battery Preheat Off", Category.CHARGING, "ZAN", "stop"),

    // ---- signalling / find car ----
    FLASH("Flash Lights", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("SETTING", "LIGHT_FLASH"))),
    HONK("Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("SETTING", "HORN"))),
    FLASH_HORN("Flash + Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("SETTING", "HORN_LIGHT_FLASH"))),

    // ---- security ----
    SENTINEL_ON("Sentry On", Category.SECURITY, "RSM", "start", listOf(ServiceParameter("SETTING", "RSM_THREE"))),
    SENTINEL_OFF("Sentry Off", Category.SECURITY, "RSM", "stop"),
    LOCKER_ON("Private Locker Lock", Category.SECURITY, "ZAD", "start", listOf(ServiceParameter("SWITCH", "ENABLE"), ServiceParameter("PASSWORD", "1234"))),
    LOCKER_OFF("Private Locker Unlock", Category.SECURITY, "ZAD", "start", listOf(ServiceParameter("SWITCH", "DISABLE"), ServiceParameter("PASSWORD", "1234"))),
    APPROACH_UNLOCK_ON("Approach Unlock On", Category.SECURITY, "DKB", "start", listOf(ServiceParameter("SWITCH", "ENABLE"))),
    APPROACH_UNLOCK_OFF("Approach Unlock Off", Category.SECURITY, "DKB", "start", listOf(ServiceParameter("SWITCH", "DISABLE"))),
    WALK_AWAY_LOCK_ON("Walk-Away Lock On", Category.SECURITY, "DKB", "start", listOf(ServiceParameter("SWITCH", "ENABLE"))),
    WALK_AWAY_LOCK_OFF("Walk-Away Lock Off", Category.SECURITY, "DKB", "start", listOf(ServiceParameter("SWITCH", "DISABLE"))),

    // ---- system ----
    WAKE_UP("Wake Vehicle", Category.SYSTEM, "RWR", "start"),
    ;

    fun toRequest(vinUserId: String?, extraParams: List<ServiceParameter> = emptyList()): RemoteControlRequest =
        RemoteControlRequest(
            serviceId = serviceId,
            command = command,
            serviceParameters = params + extraParams,
            operationScheduling = durationSec?.let { OperationScheduling(duration = it) },
            engStrtType = engStrtType,
            userId = vinUserId,
        )
}
