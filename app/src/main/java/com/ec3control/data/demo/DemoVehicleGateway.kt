package com.ec3control.data.demo

import com.ec3control.core.model.DataOrigin
import com.ec3control.core.model.VehicleSnapshot
import com.ec3control.core.vehicle.VehicleGateway

class DemoVehicleGateway : VehicleGateway {
    private var climate = false

    override suspend fun getVehicle() = snapshot()
    override suspend fun refresh() = snapshot()

    override suspend fun setClimate(enabled: Boolean): Result<Unit> {
        climate = enabled
        return Result.success(Unit)
    }

    override suspend fun startCharging() = Result.success(Unit)
    override suspend fun stopCharging() = Result.success(Unit)

    private fun snapshot() = VehicleSnapshot(
        origin = DataOrigin.DEMO,
        batteryPercent = 76,
        rangeKm = 247,
        odometerKm = 12485.0,
        batteryHealthPercent = 98.0,
        outsideTemperatureC = 21.0,
        plugged = false,
        charging = false,
        chargingPowerKw = 0.0,
        chargingTargetPercent = 80,
        climateRunning = climate,
        updatedAtEpochMillis = System.currentTimeMillis()
    )
}
