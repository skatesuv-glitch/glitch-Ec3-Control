package com.ec3control.ui

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val vehicleName: String) : ConnectionState
    data class VehicleSelectionRequired(val vehicles: List<VehicleChoice>) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class VehicleChoice(val id: String, val label: String)
