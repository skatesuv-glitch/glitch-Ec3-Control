package com.ec3control.data.stellantis

data class StellantisEndpoints(val vehiclesPath: String, val statusPath: (vehicleId: String) -> String)
