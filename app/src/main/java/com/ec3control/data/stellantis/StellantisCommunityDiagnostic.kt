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

            val userRequestUrl = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.groupe-psa.com")
                .addPathSegments("connectedcar/v4/user")
                .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                .build()
            val userProbe = http.newCall(
                Request.Builder().url(userRequestUrl).apply(headers).get().build()
            ).execute().use { probe ->
                val raw = probe.body?.string().orEmpty()
                Pair(probe.code, safeErrorDetail(raw))
            }

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
                                    val directBase = okhttp3.HttpUrl.Builder()
                                        .scheme("https")
                                        .host("api.groupe-psa.com")
                                        .addPathSegments("connectedcar/v4/user/vehicles/$associatedVehicle/status")
                                        .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                                        .addQueryParameter("locale", "es-ES")
                                        .build()
                                    val normalProbe = http.newCall(Request.Builder().url(directBase).apply(headers).get().build()).execute()
                                    val normalCode = normalProbe.code
                                    if (normalProbe.isSuccessful) {
                                        val raw = normalProbe.body?.string().orEmpty()
                                        normalProbe.close()
                                        return@withContext parseReadOnlyStatus(raw, associatedVehicle, "VIN")
                                    }
                                    normalProbe.close()
                                    val endUserProbe = http.newCall(Request.Builder().url(directBase.newBuilder().addQueryParameter("profile", "endUser").build()).apply(headers).get().build()).execute()
                                    val endUserCode = endUserProbe.code
                                    if (endUserProbe.isSuccessful) {
                                        val raw = endUserProbe.body?.string().orEmpty()
                                        endUserProbe.close()
                                        return@withContext parseReadOnlyStatus(raw, associatedVehicle, "VIN+endUser")
                                    }
                                    endUserProbe.close()

                                    // The association payload also carries a UUID-like car_association_id.
                                    // Probe it independently, read-only, without exposing its value.
                                    val associationId = association["car_association_id"]?.jsonPrimitive?.contentOrNull
                                    var associationIdCode: Int? = null
                                    var associationIdEndUserCode: Int? = null
                                    if (!associationId.isNullOrBlank()) {
                                        val associationStatusUrl = okhttp3.HttpUrl.Builder()
                                            .scheme("https")
                                            .host("api.groupe-psa.com")
                                            .addPathSegments("connectedcar/v4/user/vehicles/$associationId/status")
                                            .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                                            .addQueryParameter("locale", "es-ES")
                                            .build()
                                        val associationProbe = http.newCall(
                                            Request.Builder().url(associationStatusUrl).apply(headers).get().build()
                                        ).execute()
                                        associationIdCode = associationProbe.code
                                        if (associationProbe.isSuccessful) {
                                            val raw = associationProbe.body?.string().orEmpty()
                                            associationProbe.close()
                                            return@withContext parseReadOnlyStatus(raw, associationId, "associationId")
                                        }
                                        associationProbe.close()

                                        val associationEndUserProbe = http.newCall(
                                            Request.Builder()
                                                .url(associationStatusUrl.newBuilder().addQueryParameter("profile", "endUser").build())
                                                .apply(headers).get().build()
                                        ).execute()
                                        associationIdEndUserCode = associationEndUserProbe.code
                                        if (associationEndUserProbe.isSuccessful) {
                                            val raw = associationEndUserProbe.body?.string().orEmpty()
                                            associationEndUserProbe.close()
                                            return@withContext parseReadOnlyStatus(raw, associationId, "associationId+endUser")
                                        }
                                        associationEndUserProbe.close()
                                    }
                                    // Probe the MAUV association resource itself. The Connected Car v4
                                    // vehicle/status route returned 404 for both VIN and association UUID,
                                    // so inspect only safe response shape from the association family.
                                    val associationResourceUrl = okhttp3.HttpUrl.Builder()
                                        .scheme("https")
                                        .host("api.groupe-psa.com")
                                        .addPathSegments("applications/cvs/v4/mauv/car-associations")
                                        .addPathSegment(associationId.orEmpty())
                                        .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                                        .addQueryParameter("locale", "es-ES")
                                        .build()
                                    var associationResourceCode: Int? = null
                                    var associationResourceKeys = "n/a"
                                    var associationEntityShape = "n/a"
                                    var associationServicesShape = "n/a"
                                    var associationNotificationShape = "n/a"
                                    if (!associationId.isNullOrBlank()) {
                                        http.newCall(
                                            Request.Builder().url(associationResourceUrl).apply(headers)
                                                .header("x-transaction-id", "1234").get().build()
                                        ).execute().use { associationResource ->
                                            associationResourceCode = associationResource.code
                                            if (associationResource.isSuccessful) {
                                                val raw = associationResource.body?.string().orEmpty()
                                                val keys = Regex("\\\"([^\\\"]+)\\\"\\s*:").findAll(raw)
                                                    .map { it.groupValues[1] }
                                                    .filterNot { it.equals("vehicle", true) || it.contains("customer", true) || it.contains("id", true) }
                                                    .distinct()
                                                    .take(24)
                                                    .toList()
                                                associationResourceKeys = if (keys.isEmpty()) "none" else keys.joinToString(",")
                                                fun safeFieldShape(fieldName: String): String {
                                                    val match = Regex("\\\"" + Regex.escape(fieldName) + "\\\"\\s*:\\s*([^,}]+|\\{[^}]*\\}|\\[[^]]*\\])")
                                                        .find(raw)?.groupValues?.getOrNull(1)?.trim() ?: return "absent"
                                                    return when {
                                                        match.startsWith("{") -> {
                                                            val nestedKeys = Regex("\\\"([^\\\"]+)\\\"\\s*:").findAll(match)
                                                                .map { it.groupValues[1] }
                                                                .filterNot { it.contains("id", true) || it.contains("vin", true) || it.contains("customer", true) }
                                                                .distinct().take(16).toList()
                                                            "object(keys=" + nestedKeys.joinToString(",") + ")"
                                                        }
                                                        match.startsWith("[") -> "array"
                                                        match.startsWith("\\\"") -> "text(len=" + (match.length - 2).coerceAtLeast(0) + ")"
                                                        else -> "scalar"
                                                    }
                                                }
                                                associationEntityShape = safeFieldShape("entity")
                                                associationServicesShape = safeFieldShape("services")
                                                val notificationKey = keys.firstOrNull { it.contains("notification", true) }
                                                associationNotificationShape = notificationKey?.let { safeFieldShape(it) } ?: "absent"
                                            }
                                        }
                                    }

                                    // Read-only schema fingerprint. Never expose VIN/customer values.
                                    // This lets us compare our association shape with accounts where
                                    // Connected Car resolves a vehicle_id, without guessing endpoints.
                                    val associationKeys = association.keys.sorted().joinToString(",")
                                    val associationCount = associations.size
                                    val safeRows = associations.mapIndexed { index, element ->
                                        val row = element.jsonObject
                                        fun safeValue(key: String): String {
                                            val field = row[key] ?: return "ausente"
                                            return when (field) {
                                                is JsonPrimitive -> if (field.isString) {
                                                    when {
                                                        key == "vehicle" -> if (field.content.length == 17) "VIN17" else "texto"
                                                        field.content.isBlank() -> "vacío"
                                                        else -> field.content.take(40)
                                                    }
                                                } else field.content.take(40)
                                                is JsonArray -> "array(" + field.size + ")"
                                                is JsonObject -> "objeto(keys=" + field.keys.sorted().joinToString(",") + ")"
                                                else -> "presente"
                                            }
                                        }
                                        fun safeArrayShape(key: String): String {
                                            val array = row[key] as? JsonArray ?: return safeValue(key)
                                            if (array.isEmpty()) return "array(0)"
                                            return array.mapIndexed { itemIndex, item ->
                                                when (item) {
                                                    is JsonObject -> {
                                                        val keys = item.keys.sorted().joinToString(",")
                                                        val safeLabels = listOf("name", "type", "status", "service", "code")
                                                            .mapNotNull { label ->
                                                                item[label]?.jsonPrimitive?.contentOrNull
                                                                    ?.takeIf { it.isNotBlank() }
                                                                    ?.let { label + "=" + it.take(40) }
                                                            }.joinToString(",")
                                                        "item" + (itemIndex + 1) + "{keys=" + keys +
                                                            if (safeLabels.isNotBlank()) "; " + safeLabels + "}" else "}"
                                                    }
                                                    is JsonPrimitive -> {
                                                        val raw = item.content
                                                        val safe = when {
                                                            raw.length == 17 && raw.all { it.isLetterOrDigit() } -> "VIN17_oculto"
                                                            raw.contains("@") -> "dato_oculto"
                                                            raw.length > 80 -> "texto(" + raw.length + ")"
                                                            else -> raw.replace(Regex("[A-HJ-NPR-Z0-9]{17}"), "VIN17_oculto").take(80)
                                                        }
                                                        "item" + (itemIndex + 1) + "=" + safe
                                                    }
                                                    is JsonArray -> "item" + (itemIndex + 1) + "=array(" + item.size + ")"
                                                    else -> "item" + (itemIndex + 1) + "=presente"
                                                }
                                            }.joinToString("; ")
                                        }
                                        "#" + (index + 1) +
                                            " status=" + safeValue("car_association_status") +
                                            ", services=[" + safeArrayShape("services") + "]" +
                                            ", checks=[" + safeArrayShape("validated_checks") + "]" +
                                            ", vehicle=" + safeValue("vehicle") +
                                            ", customer=" + when (val customer = row["customer"]) { is JsonObject -> "objeto(keys=" + customer.keys.sorted().joinToString(",") + ")"; is JsonPrimitive -> "presente"; else -> if (customer != null) "presente" else "ausente" } +
                                            ", assocId=" + when (val assoc = row["car_association_id"]) { is JsonPrimitive -> "presente(len=" + assoc.content.length + ")"; else -> if (assoc != null) "presente" else "ausente" }
                                    }.joinToString(" | ")
                                    return@withContext StellantisDiagnosticState(
                                        authentication = StellantisDiagnosticState.Check.OK,
                                        vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                                        vehicleStatus = StellantisDiagnosticState.Check.PENDING,
                                        message = "VIN confirmado. /user=" + userProbe.first +
                                            "; /user/vehicles=40400; statusVIN=" + normalCode +
                                            "; statusVIN+endUser=" + endUserCode +
                                            "; statusAssocId=" + (associationIdCode ?: "n/a") +
                                            "; statusAssocId+endUser=" + (associationIdEndUserCode ?: "n/a") +
                                            "; mauvAssocResource=" + (associationResourceCode ?: "n/a") +
                                            "; mauvKeys=" + associationResourceKeys +
                                            "; entityShape=" + associationEntityShape +
                                            "; servicesShape=" + associationServicesShape +
                                            "; notificationShape=" + associationNotificationShape + ". " +
                                            "Asociaciones=" + associationCount + ". " + safeRows +
                                            ". keys=[" + associationKeys + "]" +
                                            ". IDs, VIN y datos personales ocultos. Solo lectura."
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
                val primaryStatus = http.newCall(
                    Request.Builder().url(statusUrl).apply(headers).get().build()
                ).execute()
                val statusResponse = if (primaryStatus.isSuccessful) {
                    primaryStatus
                } else {
                    val primaryCode = primaryStatus.code
                    primaryStatus.close()
                    val endUserUrl = statusUrl.newBuilder().addQueryParameter("profile", "endUser").build()
                    val fallbackResponse = http.newCall(
                        Request.Builder().url(endUserUrl).apply(headers).get().build()
                    ).execute()
                    if (!fallbackResponse.isSuccessful) {
                        val fallbackCode = fallbackResponse.code
                        val detail = safeErrorDetail(fallbackResponse.body?.string().orEmpty())
                        fallbackResponse.close()
                        return@withContext StellantisDiagnosticState(
                            authentication = StellantisDiagnosticState.Check.OK,
                            vehicleDiscovery = StellantisDiagnosticState.Check.OK,
                            vehicleStatus = StellantisDiagnosticState.Check.PENDING,
                            vehicleId = id,
                            message = "Status solo lectura: normal HTTP " + primaryCode + "; profile=endUser HTTP " + fallbackCode + (detail?.let { " | " + it } ?: "")
                        )
                    }
                    fallbackResponse
                }
                statusResponse.use { statusResponse ->

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

    private fun parseReadOnlyStatus(raw: String, vehicleId: String, source: String): StellantisDiagnosticState {
        val status = Json.parseToJsonElement(raw).jsonObject
        val energy = (status["energy"] ?: status["energies"])?.jsonArray?.firstOrNull()?.jsonObject
        val chargeStatus = energy?.get("charging")?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        return StellantisDiagnosticState(
            authentication = StellantisDiagnosticState.Check.OK,
            vehicleDiscovery = StellantisDiagnosticState.Check.OK,
            vehicleStatus = StellantisDiagnosticState.Check.OK,
            batteryPercent = energy?.get("level")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
            rangeKm = energy?.get("autonomy")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
            charging = chargeStatus?.equals("InProgress", true),
            vehicleId = vehicleId,
            message = "Estado real recibido en solo lectura vía " + source + "."
        )
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
