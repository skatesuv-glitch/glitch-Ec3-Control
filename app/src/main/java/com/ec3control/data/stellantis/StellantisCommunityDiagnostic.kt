package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

data class StellantisDiagnosticState(
    val authentication: Check = Check.PENDING,
    val vehicleDiscovery: Check = Check.PENDING,
    val vehicleStatus: Check = Check.PENDING,
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val charging: Boolean? = null,
    val vehicleId: String? = null,
    val message: String = "Experimental connector not authenticated"
) {
    enum class Check { PENDING, OK, ERROR }
}

interface StellantisCommunityDiagnostic {
    suspend fun readStatus(): StellantisDiagnosticState
}

data class StellantisRuntimeAuth(
    val accessToken: String,
    val realm: String = "clientsB2CCitroen",
    val apiBaseUrl: String = "https://api.groupe-psa.com/connectedcar"
)

class SafeStellantisCommunityDiagnostic(
    private val auth: StellantisRuntimeAuth? = null,
    private val http: OkHttpClient = OkHttpClient()
) : StellantisCommunityDiagnostic {

    override suspend fun readStatus(): StellantisDiagnosticState = withContext(Dispatchers.IO) {
        val a = auth ?: return@withContext StellantisDiagnosticState(
            message = "OAuth pendiente. No se ha enviado ninguna orden al coche."
        )
        try {
            val headers: Request.Builder.() -> Unit = {
                header("Authorization", "Bearer " + a.accessToken)
                header("x-introspect-realm", a.realm)
            }
            val vehiclesUrl = a.apiBaseUrl.trimEnd('/') + "/v4/user/vehicles"
            val vehicleResponse = http.newCall(Request.Builder().url(vehiclesUrl).apply(headers).get().build()).execute()
            vehicleResponse.use { response ->
                if (!response.isSuccessful) return@withContext httpError(response.code)
                val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val id = root["vehicles"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (id.isNullOrBlank()) return@withContext StellantisDiagnosticState(
                    authentication = StellantisDiagnosticState.Check.OK,
                    vehicleDiscovery = StellantisDiagnosticState.Check.ERROR,
                    message = "OAuth válido, pero no se encontró ningún vehículo."
                )

                val statusUrl = "$vehiclesUrl/$id/status"
                http.newCall(Request.Builder().url(statusUrl).apply(headers).get().build()).execute().use { statusResponse ->
                    if (!statusResponse.isSuccessful) return@withContext httpError(statusResponse.code, id)
                    val status = Json.parseToJsonElement(statusResponse.body?.string().orEmpty()).jsonObject
                    val energy = (status["energy"] ?: status["energies"])?.jsonArray?.firstOrNull()?.jsonObject
                    val battery = energy?.get("level")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    val range = energy?.get("autonomy")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    val chargeStatus = energy?.get("charging")?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                        ?: energy?.get("extension")?.jsonObject?.get("electric")?.jsonObject
                            ?.get("charging")?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                    StellantisDiagnosticState(
                        authentication = StellantisDiagnosticState.Check.OK,
                        vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                        vehicleStatus = StellantisDiagnosticState.Check.OK,
                        batteryPercent = battery,
                        rangeKm = range,
                        charging = chargeStatus?.equals("InProgress", true),
                        vehicleId = id,
                        message = "Estado real recibido en solo lectura."
                    )
                }
            }
        } catch (e: Exception) {
            StellantisDiagnosticState(
                authentication = StellantisDiagnosticState.Check.ERROR,
                message = "Error de conexión: " + (e.message ?: e::class.java.simpleName)
            )
        }
    }

    private fun httpError(code: Int, vehicleId: String? = null) = StellantisDiagnosticState(
        authentication = if (code == 401 || code == 403) StellantisDiagnosticState.Check.ERROR else StellantisDiagnosticState.Check.OK,
        vehicleDiscovery = if (vehicleId != null) StellantisDiagnosticState.Check.OK else StellantisDiagnosticState.Check.PENDING,
        vehicleId = vehicleId,
        message = "Stellantis respondió HTTP $code."
    )
}
