package com.ec3control.data.stellantis

/**
 * Network boundary for vehicle discovery/status.
 * Concrete HTTP endpoints/auth headers live behind this contract.
 */
interface StellantisApi {
    suspend fun discoverVehicles(): List<StellantisVehicle>
    suspend fun getStatus(vehicleId: String): StellantisStatus
}
