package com.ec3control.data.stellantis

data class StellantisVehicle(val id: String, val vin: String?, val label: String?)

data class StellantisStatus(
    val batteryLevelPercent: Double?,
    val electricRangeKm: Int?,
    val odometerKm: Double?,
    val batteryHealthPercent: Double?,
    val batteryCapacityWh: Double?,
    val batteryResidualWh: Double?,
    val plugged: Boolean?,
    val chargingStatus: String?,
    val chargingRate: Double?,
    val chargingRemainingTime: String?,
    val preconditioningStatus: String?,
    val outsideTemperatureC: Double?,
    val updatedAtEpochMillis: Long
)
