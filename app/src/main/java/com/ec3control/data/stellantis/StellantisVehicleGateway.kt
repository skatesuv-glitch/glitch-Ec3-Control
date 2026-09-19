package com.ec3control.data.stellantis

import com.ec3control.core.model.VehicleSnapshot
import com.ec3control.core.vehicle.VehicleGateway

/**
 * Frontera de la conexión real.
 *
 * La autenticación y endpoints Stellantis se implementarán aquí sin filtrar
 * credenciales ni modelos de API hacia la UI.
 */
class StellantisVehicleGateway : VehicleGateway {
    override suspend fun getVehicle(): VehicleSnapshot =
        error("Stellantis connection not configured")

    override suspend fun refresh(): VehicleSnapshot =
        error("Stellantis connection not configured")

    override suspend fun setClimate(enabled: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("Stellantis connection not configured"))

    override suspend fun startCharging(): Result<Unit> =
        Result.failure(IllegalStateException("Stellantis connection not configured"))

    override suspend fun stopCharging(): Result<Unit> =
        Result.failure(IllegalStateException("Stellantis connection not configured"))
}
