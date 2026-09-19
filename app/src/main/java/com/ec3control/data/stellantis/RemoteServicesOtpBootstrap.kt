package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RemoteServicesSmsResult(
    val httpCode: Int,
    val accepted: Boolean,
    val message: String
)

/**
 * Starts the official/community RemoteServices OTP bootstrap.
 * It requests the SMS only after an explicit tap by the user.
 * No SMS code, PIN or vehicle command is sent by this class.
 */
class RemoteServicesOtpBootstrap(
    private val http: OkHttpClient = OkHttpClient()
) {
    suspend fun requestSms(accessToken: String, realm: String = "clientsB2CCitroen"): RemoteServicesSmsResult =
        withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.groupe-psa.com")
                .addPathSegments("applications/cvs/v4/mobile/smsCode")
                .addQueryParameter("client_id", com.ec3control.BuildConfig.CITROEN_CLIENT_ID)
                .addQueryParameter("locale", "es-ES")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("x-introspect-realm", realm)
                .header("User-Agent", "okhttp/4.8.0")
                .header("Accept", "application/hal+json")
                .post(ByteArray(0).toRequestBody(null))
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val detail = safeDetail(raw)
                RemoteServicesSmsResult(
                    httpCode = response.code,
                    accepted = response.isSuccessful,
                    message = buildString {
                        append("Solicitud SMS HTTP ")
                        append(response.code)
                        if (detail != null) {
                            append(": ")
                            append(detail)
                        }
                        if (response.isSuccessful) append(". Revisa el SMS en tu móvil.")
                        else append(". No se ha enviado ninguna orden al vehículo.")
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
