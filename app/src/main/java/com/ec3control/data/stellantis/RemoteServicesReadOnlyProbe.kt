package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive\nimport kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RemoteServicesProbe(
    val httpCode: Int,
    val available: Boolean,
    val message: String
)

/**
 * Read-only V16 probe for the RemoteServices authentication gate.
 *
 * This deliberately does NOT implement OTP generation or MQTT publishing yet.
 * It only checks the documented/community token endpoint with the user's OAuth
 * token, so we can learn which prerequisite Stellantis asks for without sending
 * any command to the vehicle.
 */
class RemoteServicesReadOnlyProbe(
    private val http: OkHttpClient = OkHttpClient()
) {
    suspend fun probe(accessToken: String, realm: String = "clientsB2CCitroen"): RemoteServicesProbe =
        withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.groupe-psa.com")
                .addPathSegments("connectedcar/v4/virtualkey/remoteaccess/token")
                .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                .addQueryParameter("locale", "es-ES")
                .build()

            // Intentionally invalid/non-secret bootstrap value. We do not ask for
            // SMS/PIN until the server confirms this account exposes RemoteServices.
            val body = """{"grant_type":"password","password":""}"""
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("x-introspect-realm", realm)
                .header("User-Agent", "okhttp/4.8.0")
                .header("Accept", "application/hal+json")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val detail = safeDetail(raw)
                RemoteServicesProbe(
                    httpCode = response.code,
                    available = response.code != 404,
                    message = buildString {
                        append("RemoteServices HTTP ")
                        append(response.code)
                        if (detail != null) {
                            append(": ")
                            append(detail)
                        }
                        append(". No se ha enviado ninguna orden al vehículo.")
                    }
                )
            }
        }

    private fun safeDetail(raw: String): String? = try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        listOf("error", "error_description", "httpMessage", "moreInformation", "message", "code")
            .mapNotNull { key -> obj[key]?.jsonPrimitive?.content?.let { "$key=$it" } }
            .joinToString(" | ")
            .takeIf { it.isNotBlank() }
            ?.take(300)
    } catch (_: Exception) {
        null
    }
}
