package com.ec3control.data.stellantis

/**
 * Provider-facing DTOs. Kept separate from VehicleSnapshot so API changes
 * do not leak into the rest of EC3-Control.
 */
data class StellantisVehicle(
    val id: String,
    val vin: String?,
    val label: String?
)

data class StellantisStatus(
    val batteryLevelPercent: Double?,
    val electricRangeKm: Int?,
    val odometerKm: Double?,
    val batteryHealthPercent: Double?,
    val chargingStatus: String?,
    val chargingRate: Double?,
    val chargingRemainingTime: String?,
    val preconditioningStatus: String?,
    val outsideTemperatureC: Double?,
    val updatedAtEpochMillis: Long
)
