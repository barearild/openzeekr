package com.openzeekr.app.remote

import com.openzeekr.app.net.model.OperationScheduling
import com.openzeekr.app.net.model.RemoteControlRequest
import com.openzeekr.app.net.model.RemoteControlSetting
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
    // From Cmd$Companion (stock): locking = RDL/"start", unlocking/opening = RDU/"stop".
    // Params are LOWERCASE (door=all, target=trunk/hood). Frunk = RDU/start target=hood.
    UNLOCK("Unlock", Category.DOORS, "RDU", "stop", listOf(ServiceParameter("door", "all"))),
    LOCK("Lock", Category.DOORS, "RDL", "start", listOf(ServiceParameter("door", "all"))),
    TRUNK_UNLOCK("Open Trunk", Category.DOORS, "RDU", "stop", listOf(ServiceParameter("target", "trunk"))),
    TRUNK_LOCK("Lock Trunk", Category.DOORS, "RDL", "start", listOf(ServiceParameter("target", "trunk"))),
    FRONT_TRUNK("Open Frunk", Category.DOORS, "RDU", "start", listOf(ServiceParameter("target", "hood"))),
    CHARGE_LID_OPEN("Open Charge Lid", Category.DOORS, "RDO", "start", listOf(ServiceParameter("target", "front-charge-lid"))),
    CHARGE_LID_CLOSE("Close Charge Lid", Category.DOORS, "RDC", "stop", listOf(ServiceParameter("target", "front-charge-lid"))),

    // ---- climate ---- (serviceId RCE — NOT RCE_2; rce.conditioner selects the function)
    AC_ON("A/C On", Category.CLIMATE, "RCE", "start", listOf(ServiceParameter("rce.conditioner", "1"))),
    AC_OFF("A/C Off", Category.CLIMATE, "RCE", "stop", listOf(ServiceParameter("rce.conditioner", "1"))),
    CABIN_ON("Cabin Precondition On", Category.CLIMATE, "RCC", "start", listOf(ServiceParameter("rcc.conditioner", "50"), ServiceParameter("rcc.ventilation", "0")), durationSec = 6),
    CABIN_OFF("Cabin Precondition Off", Category.CLIMATE, "RCC", "stop", listOf(ServiceParameter("rcc.conditioner", "50"), ServiceParameter("rcc.ventilation", "0"))),
    DEFROST_ON("Defrost On", Category.CLIMATE, "RCE", "start", listOf(ServiceParameter("rce.conditioner", "2")), durationSec = 90),
    DEFROST_OFF("Defrost Off", Category.CLIMATE, "RCE", "stop", listOf(ServiceParameter("rce.conditioner", "2"))),
    SEAT_HEAT_ON("Seat Heat On", Category.COMFORT, "RCE", "start", listOf(ServiceParameter("rce.conditioner", "3"))),
    SEAT_HEAT_OFF("Seat Heat Off", Category.COMFORT, "RCE", "stop", listOf(ServiceParameter("rce.conditioner", "3"))),
    STEER_WHEEL_ON("Steering Wheel Heat On", Category.COMFORT, "RCE", "start", listOf(ServiceParameter("rce.heat", "steering_wheel"), ServiceParameter("rce.conditioner", "5"))),
    STEER_WHEEL_OFF("Steering Wheel Heat Off", Category.COMFORT, "RCE", "stop", listOf(ServiceParameter("rce.heat", "steering_wheel"), ServiceParameter("rce.conditioner", "5"))),
    FRAGRANCE_ON("Fragrance On", Category.COMFORT, "RFD", "start"),
    FRAGRANCE_OFF("Fragrance Off", Category.COMFORT, "RFD", "stop", listOf(ServiceParameter("channel_id", "0"), ServiceParameter("level", "0"))),
    FRIDGE_ON("Fridge On", Category.COMFORT, "ZAE", "start"),
    FRIDGE_OFF("Fridge Off", Category.COMFORT, "ZAE", "stop"),

    // ---- engine / RES ---- (engStrtType=1 as a param; duration via operationScheduling=60s)
    ENGINE_START("Remote Start", Category.CLIMATE, "RES", "start", engStrtType = "1", durationSec = 60),
    ENGINE_STOP("Remote Stop", Category.CLIMATE, "RES", "stop", engStrtType = "1"),

    // ---- windows / sunroof ---- (serviceId RWS — NOT RWS_2; target=window/sunroof/sunshade/ventilate)
    WINDOW_OPEN("Windows Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "window"))),
    WINDOW_CLOSE("Windows Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "window"))),
    WINDOW_VENT("Windows Vent", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "ventilate"))),
    SUNROOF_OPEN("Sunroof Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "sunroof"))),
    SUNROOF_CLOSE("Sunroof Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "sunroof"))),
    SUNSHADE_OPEN("Sunshade Open", Category.WINDOWS, "RWS", "start", listOf(ServiceParameter("target", "sunshade"))),
    SUNSHADE_CLOSE("Sunshade Close", Category.WINDOWS, "RWS", "stop", listOf(ServiceParameter("target", "sunshade"))),

    // ---- charging ---- (RCS; rcs.restart/terminate=1, SOC via rcs.setting)
    CHARGING_ON("Start Charging", Category.CHARGING, "RCS", "start", listOf(ServiceParameter("rcs.restart", "1"))),
    CHARGING_OFF("Stop Charging", Category.CHARGING, "RCS", "stop", listOf(ServiceParameter("rcs.terminate", "1"))),
    SET_CHARGE_SOC("Set Charge Limit", Category.CHARGING, "RCS", "start", listOf(ServiceParameter("rcs.setting", "1"), ServiceParameter("altCurrent", "1"))),
    BATTERY_PREHEAT_ON("Battery Preheat On", Category.CHARGING, "ZAN", "start"),
    BATTERY_PREHEAT_OFF("Battery Preheat Off", Category.CHARGING, "ZAN", "stop"),

    // ---- signalling / find car ---- (RHL; rhl = horn / light-flash / horn-light-flash)
    FLASH("Flash Lights", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "light-flash"))),
    HONK("Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "horn"))),
    FLASH_HORN("Flash + Horn", Category.SIGNAL, "RHL", "start", listOf(ServiceParameter("rhl", "horn-light-flash"))),

    // ---- security ---- (RSM sentry sub-mode via rsm=<n>; private locker via RDL/RDU target=private-lock)
    SENTINEL_ON("Sentry On", Category.SECURITY, "RSM", "start", listOf(ServiceParameter("rsm", "1"))),
    SENTINEL_OFF("Sentry Off", Category.SECURITY, "RSM", "stop", listOf(ServiceParameter("rsm", "1"))),
    LOCKER_ON("Private Locker Lock", Category.SECURITY, "RDL", "start", listOf(ServiceParameter("target", "private-lock"), ServiceParameter("password", "1234"))),
    LOCKER_OFF("Private Locker Unlock", Category.SECURITY, "RDU", "stop", listOf(ServiceParameter("target", "private-lock"), ServiceParameter("password", "1234"))),
    ;

    fun toRequest(extraParams: List<ServiceParameter> = emptyList()): RemoteControlRequest {
        // engStrtType is a serviceParameter (not a top-level field) in the stock request.
        val allParams = buildList {
            addAll(params)
            addAll(extraParams)
            engStrtType?.let { add(ServiceParameter("engStrtType", it)) }
        }
        return RemoteControlRequest(
            command = command,
            serviceId = serviceId,
            setting = RemoteControlSetting(
                serviceParameters = allParams,
                operationScheduling = durationSec?.let { OperationScheduling(duration = it) },
            ),
        )
    }
}
