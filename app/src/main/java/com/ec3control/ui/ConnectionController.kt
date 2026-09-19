package com.ec3control.ui

import com.ec3control.auth.AuthGateway
import com.ec3control.data.stellantis.StellantisApi
import com.ec3control.data.stellantis.StellantisVehicle
import com.ec3control.data.stellantis.VehicleSelection
import com.ec3control.data.stellantis.VehicleSelector

class ConnectionController(
    private val auth: AuthGateway,
    private val api: StellantisApi
) {
    suspend fun restore(): ConnectionState {
        val session = auth.restoreSession() ?: return ConnectionState.Disconnected
        if (session.isExpired()) return ConnectionState.Disconnected
        return discover()
    }

    suspend fun discover(): ConnectionState = runCatching {
        when (val result = VehicleSelector.from(api.discoverVehicles())) {
            VehicleSelection.None -> ConnectionState.Error("No hay vehículos asociados a esta cuenta.")
            is VehicleSelection.Selected -> ConnectionState.Connected(displayName(result.vehicle))
            is VehicleSelection.Choose -> ConnectionState.VehicleSelectionRequired(
                result.vehicles.map { VehicleChoice(it.id, displayName(it)) }
            )
        }
    }.getOrElse { ConnectionState.Error(it.message ?: "No se pudo consultar MyCitroën.") }

    private fun displayName(vehicle: StellantisVehicle): String =
        vehicle.label ?: vehicle.vin?.let { "Citroën ••••" + it.takeLast(4) } ?: "Citroën"
}
