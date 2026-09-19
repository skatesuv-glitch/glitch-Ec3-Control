package com.ec3control.core.vehicle

import com.ec3control.core.model.VehicleSnapshot

interface VehicleGateway {
    suspend fun getVehicle(): VehicleSnapshot
    suspend fun refresh(): VehicleSnapshot
    suspend fun setClimate(enabled: Boolean): Result<Unit>
    suspend fun startCharging(): Result<Unit>
    suspend fun stopCharging(): Result<Unit>
}
