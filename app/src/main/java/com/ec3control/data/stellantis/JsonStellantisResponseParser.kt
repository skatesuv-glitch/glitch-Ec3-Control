package com.ec3control.data.stellantis

import kotlinx.serialization.json.*
import java.time.Instant

class JsonStellantisResponseParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : StellantisResponseParser {

    override fun vehicles(json: String): List<StellantisVehicle> {
        val root = this.json.parseToJsonElement(json).jsonObject
        val array = root["_embedded"]?.jsonObject?.get("vehicles")?.jsonArray
            ?: root["vehicles"]?.jsonArray ?: JsonArray(emptyList())
        return array.mapNotNull { node ->
            val o = node.jsonObject
            val vin = o.string("vin")
            val id = o.string("id") ?: vin ?: return@mapNotNull null
            StellantisVehicle(id, vin, o.string("label") ?: o.string("name"))
        }
    }

    override fun status(json: String): StellantisStatus {
        val root = this.json.parseToJsonElement(json).jsonObject
        val energy = root["energies"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: root["energy"]?.jsonArray?.firstOrNull()?.jsonObject
        val electric = energy?.get("extension")?.jsonObject?.get("electric")?.jsonObject
        val charging = electric?.get("charging")?.jsonObject ?: energy?.get("charging")?.jsonObject
        val battery = electric?.get("battery")?.jsonObject ?: energy?.get("battery")?.jsonObject
        val load = battery?.get("load")?.jsonObject
        val health = battery?.get("health")?.jsonObject
        val odometer = root["odometer"]?.jsonObject
        val climate = (root["preconditioning"] ?: root["preconditionning"])?.jsonObject
            ?.get("airConditioning")?.jsonObject

        return StellantisStatus(
            batteryLevelPercent = energy.double("level"),
            electricRangeKm = energy.double("autonomy")?.toInt(),
            odometerKm = odometer.double("mileage"),
            batteryHealthPercent = health.double("resistance"),
            batteryCapacityWh = load.double("capacity"),
            batteryResidualWh = load.double("residual"),
            plugged = charging.boolean("plugged"),
            chargingStatus = charging.string("status"),
            chargingRate = charging.double("chargingRate"),
            chargingRemainingTime = charging.string("remainingTime"),
            preconditioningStatus = climate.string("status"),
            outsideTemperatureC = root["environment"]?.jsonObject?.double("airTemperature"),
            updatedAtEpochMillis = newestTimestamp(root, energy, odometer, climate)
        )
    }

    private fun newestTimestamp(vararg objects: JsonObject?): Long =
        objects.mapNotNull { it.string("updatedAt") ?: it.string("createdAt") }
            .mapNotNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            .maxOrNull() ?: System.currentTimeMillis()

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject?.double(key: String): Double? =
        this?.get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject?.boolean(key: String): Boolean? =
        this?.get(key)?.jsonPrimitive?.booleanOrNull
}
