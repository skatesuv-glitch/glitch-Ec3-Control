package com.ec3control.data.stellantis

import com.ec3control.core.model.DataOrigin
import com.ec3control.core.model.VehicleSnapshot

object StellantisMapper {
    fun toSnapshot(status: StellantisStatus): VehicleSnapshot = VehicleSnapshot(
        origin = DataOrigin.STELLANTIS,
        batteryPercent = status.batteryLevelPercent?.toInt(),
        rangeKm = status.electricRangeKm,
        odometerKm = status.odometerKm,
        batteryHealthPercent = status.batteryHealthPercent,
        outsideTemperatureC = status.outsideTemperatureC,
        plugged = status.plugged ?: inferPlugged(status.chargingStatus),
        charging = status.chargingStatus?.equals("InProgress", ignoreCase = true),
        chargingPowerKw = status.chargingRate,
        chargingTargetPercent = null,
        climateRunning = status.preconditioningStatus?.equals("Enabled", ignoreCase = true),
        updatedAtEpochMillis = status.updatedAtEpochMillis
    )
    private fun inferPlugged(status: String?): Boolean? = when {
        status == null -> null
        status.equals("Disconnected", true) -> false
        else -> true
    }
}
