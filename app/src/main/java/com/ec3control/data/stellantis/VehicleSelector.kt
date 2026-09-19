package com.ec3control.data.stellantis

sealed interface VehicleSelection {
    data class Selected(val vehicle: StellantisVehicle) : VehicleSelection
    data class Choose(val vehicles: List<StellantisVehicle>) : VehicleSelection
    data object None : VehicleSelection
}

object VehicleSelector {
    fun from(vehicles: List<StellantisVehicle>): VehicleSelection = when (vehicles.size) {
        0 -> VehicleSelection.None
        1 -> VehicleSelection.Selected(vehicles.first())
        else -> VehicleSelection.Choose(vehicles)
    }
}
