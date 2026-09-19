package com.ec3control.core.model

enum class DataOrigin { DEMO, STELLANTIS }

data class VehicleSnapshot(
    val origin: DataOrigin,
    val batteryPercent: Int?,
    val rangeKm: Int?,
    val odometerKm: Double?,
    val batteryHealthPercent: Double?,
    val outsideTemperatureC: Double?,
    val plugged: Boolean?,
    val charging: Boolean?,
    val chargingPowerKw: Double?,
    val chargingTargetPercent: Int?,
    val climateRunning: Boolean?,
    val updatedAtEpochMillis: Long
)
