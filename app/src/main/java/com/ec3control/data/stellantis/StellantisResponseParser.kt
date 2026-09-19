package com.ec3control.data.stellantis

interface StellantisResponseParser {
    fun vehicles(json: String): List<StellantisVehicle>
    fun status(json: String): StellantisStatus
}
