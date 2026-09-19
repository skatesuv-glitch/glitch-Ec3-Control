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
                header("User-Agent", "okhttp/4.8.0")
                header("Accept", "application/hal+json")
            }
            val vehiclesUrl = a.apiBaseUrl.trimEnd('/') + "/v4/user/vehicles"
            val vehiclesRequestUrl = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.groupe-psa.com")
                .addPathSegments("connectedcar/v4/user/vehicles")
                .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                .addQueryParameter("locale", "es-ES")
                .build()
            val vehicleResponse = http.newCall(
                Request.Builder().url(vehiclesRequestUrl).apply(headers).get().build()
            ).execute()

            vehicleResponse.use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        val associationUrl = okhttp3.HttpUrl.Builder()
                            .scheme("https")
                            .host("api.groupe-psa.com")
                            .addPathSegments("applications/cvs/v4/mauv/car-associations")
                            .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                            .addQueryParameter("locale", "es-ES")
                            .build()
                        http.newCall(
                            Request.Builder().url(associationUrl).apply(headers)
                                .header("x-transaction-id", "1234").get().build()
                        ).execute().use { associationResponse ->
                            val associationRaw = associationResponse.body?.string().orEmpty()
                            if (associationResponse.isSuccessful) {
                                val associations = Json.parseToJsonElement(associationRaw).jsonArray
                                val association = associations.firstOrNull()?.jsonObject
                                val associatedVehicle = association
                                    ?.get("vehicle")?.jsonPrimitive?.contentOrNull
                                if (!associatedVehicle.isNullOrBlank()) {
                                    return@withContext StellantisDiagnosticState(
                                        authentication = StellantisDiagnosticState.Check.OK,
                                        vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                                        vehicleStatus = StellantisDiagnosticState.Check.PENDING,
                                        message = "VIN confirmado. La asociación no expone el vehicle_id. " +
                                            "La lista /user/vehicles es la fuente documentada del ID y respondió 40400."
                                    )
                                }
                            }
                            return@withContext httpError(
                                associationResponse.code,
                                detail = safeErrorDetail(associationRaw)
                                    ?: "No se encontró asociación de vehículo"
                            )
                        }
                    }
                    return@withContext httpError(
                        response.code,
                        detail = safeErrorDetail(response.body?.string().orEmpty())
                    )
                }

                val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val id = root["_embedded"]?.jsonObject
                    ?.get("vehicles")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["vehicles"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("id")?.jsonPrimitive?.contentOrNull

                if (id.isNullOrBlank()) {
                    return@withContext StellantisDiagnosticState(
                        authentication = StellantisDiagnosticState.Check.OK,
                        vehicleDiscovery = StellantisDiagnosticState.Check.ERROR,
                        message = "OAuth válido, pero no se encontró ningún vehículo."
                    )
                }

                val statusUrl = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("api.groupe-psa.com")
                    .addPathSegments("connectedcar/v4/user/vehicles/$id/status")
                    .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                    .addQueryParameter("locale", "es-ES")
                    .build()
                    .toString()
                http.newCall(
                    Request.Builder().url(statusUrl).apply(headers).get().build()
                ).execute().use { statusResponse ->
                    if (!statusResponse.isSuccessful) {
                        return@withContext httpError(
                            statusResponse.code,
                            id,
                            safeErrorDetail(statusResponse.body?.string().orEmpty())
                        )
                    }

                    val status = Json.parseToJsonElement(
                        statusResponse.body?.string().orEmpty()
                    ).jsonObject
                    val energy = (status["energy"] ?: status["energies"])
                        ?.jsonArray?.firstOrNull()?.jsonObject
                    val battery = energy?.get("level")?.jsonPrimitive
                        ?.contentOrNull?.toDoubleOrNull()?.toInt()
                    val range = energy?.get("autonomy")?.jsonPrimitive
                        ?.contentOrNull?.toDoubleOrNull()?.toInt()
                    val chargeStatus = energy?.get("charging")?.jsonObject
                        ?.get("status")?.jsonPrimitive?.contentOrNull
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


    private fun readVehicleStatus(
        auth: StellantisRuntimeAuth,
        vehicleId: String,
        headers: Request.Builder.() -> Unit,
        identifierKind: String = "ID"
    ): StellantisDiagnosticState {
        val statusUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.groupe-psa.com")
            .addPathSegments("connectedcar/v4/user/vehicles/$vehicleId/status")
            .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
            .addQueryParameter("locale", "es-ES")
            .build()
        http.newCall(Request.Builder().url(statusUrl).apply(headers).get().build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return StellantisDiagnosticState(
                    authentication = StellantisDiagnosticState.Check.OK,
                    vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                    vehicleStatus = StellantisDiagnosticState.Check.PENDING,
                    vehicleId = vehicleId,
                    message = buildString {
                        append("Status HTTP ")
                        append(response.code)
                        append(". Identificador de asociación: ")
                        append(identifierKind)
                        append(" (valor oculto)")
                        safeErrorDetail(raw)?.let { append(" | "); append(it) }
                    }
                )
            }
            val status = Json.parseToJsonElement(raw).jsonObject
            val energy = (status["energy"] ?: status["energies"])?.jsonArray?.firstOrNull()?.jsonObject
            return StellantisDiagnosticState(
                authentication = StellantisDiagnosticState.Check.OK,
                vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                vehicleStatus = StellantisDiagnosticState.Check.OK,
                batteryPercent = energy?.get("level")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
                rangeKm = energy?.get("autonomy")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
                vehicleId = vehicleId,
                message = "Vehículo asociado encontrado. Estado real recibido en solo lectura."
            )
        }
    }

    private fun safeErrorDetail(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            listOf(
                "error", "error_description", "httpMessage",
                "moreInformation", "message", "code"
            ).mapNotNull { key ->
                obj[key]?.jsonPrimitive?.contentOrNull?.let { value -> "$key=$value" }
            }.joinToString(" | ").takeIf { it.isNotBlank() }?.take(300)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpError(
        code: Int,
        vehicleId: String? = null,
        detail: String? = null
    ) = StellantisDiagnosticState(
        authentication = if (code == 401 || code == 403) {
            StellantisDiagnosticState.Check.ERROR
        } else {
            StellantisDiagnosticState.Check.OK
        },
        vehicleDiscovery = if (vehicleId != null) {
            StellantisDiagnosticState.Check.OK
        } else {
            StellantisDiagnosticState.Check.PENDING
        },
        vehicleId = vehicleId,
        message = buildString {
            append("Stellantis respondió HTTP $code")
            if (!detail.isNullOrBlank()) append(": $detail")
            append(".")
        }
    )
}
