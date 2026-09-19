package com.ec3control.data.stellantis

import com.ec3control.core.model.VehicleSnapshot

class VehicleRepository(private val api: StellantisApi) {
    private var selected: StellantisVehicle? = null

    suspend fun vehicles(): List<StellantisVehicle> = api.discoverVehicles()

    suspend fun select(vehicle: StellantisVehicle) {
        selected = vehicle
    }

    suspend fun current(): VehicleSnapshot {
        val vehicle = selected ?: api.discoverVehicles().singleOrNull()
            ?: error("Vehicle selection required")
        selected = vehicle
        return StellantisMapper.toSnapshot(api.getStatus(vehicle.id))
    }
}
