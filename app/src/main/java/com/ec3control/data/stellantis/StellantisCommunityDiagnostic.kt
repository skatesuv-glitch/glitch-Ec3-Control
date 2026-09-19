package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Experimental Plan B connector.
 *
 * Read-only by design. It cannot issue vehicle commands.
 * Authentication secrets are supplied at runtime and are never committed.
 */
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

/**
 * Runtime configuration obtained from the OAuth flow.
 * Do not persist these values in source code or logs.
 */
data class StellantisRuntimeAuth(
    val accessToken: String,
    val clientId: String,
    val apiBaseUrl: String
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
            val vehiclesRequest = Request.Builder()
                .url(a.apiBaseUrl.trimEnd('/') + "/v4/user/vehicles")
                .header("Authorization", "Bearer " + a.accessToken)
                .header("x-introspect-realm", "clientsB2C")
                .header("client-id", a.clientId)
                .get()
                .build()

            http.newCall(vehiclesRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext StellantisDiagnosticState(
                        authentication = if (response.code == 401 || response.code == 403)
                            StellantisDiagnosticState.Check.ERROR
                        else StellantisDiagnosticState.Check.OK,
                        message = "Stellantis respondió HTTP " + response.code + "."
                    )
                }

                val body = response.body?.string().orEmpty()
                val root = Json.parseToJsonElement(body).jsonObject
                val vehicles = root["vehicles"]?.jsonArray
                val first = vehicles?.firstOrNull()?.jsonObject
                val id = first?.get("id")?.jsonPrimitive?.content

                if (id.isNullOrBlank()) {
                    return@withContext StellantisDiagnosticState(
                        authentication = StellantisDiagnosticState.Check.OK,
                        vehicleDiscovery = StellantisDiagnosticState.Check.ERROR,
                        message = "OAuth válido, pero no se encontró ningún vehículo."
                    )
                }

                StellantisDiagnosticState(
                    authentication = StellantisDiagnosticState.Check.OK,
                    vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                    vehicleId = id,
                    message = "Vehículo encontrado. Siguiente prueba: estado de batería en solo lectura."
                )
            }
        } catch (e: Exception) {
            StellantisDiagnosticState(
                authentication = StellantisDiagnosticState.Check.ERROR,
                message = "Error de conexión: " + (e.message ?: e::class.java.simpleName)
            )
        }
    }
}
