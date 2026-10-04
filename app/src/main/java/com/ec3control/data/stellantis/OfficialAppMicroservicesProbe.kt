package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

data class MicroservicesProbeResult(
    val sessionV2: String = "Pendiente",
    val sessionV1: String = "Pendiente",
    val user: String = "No probado",
    val devices: String = "No probado",
    val tokenObtained: Boolean = false,
    val note: String = "Solo lectura"
)

class OfficialAppMicroservicesProbe(
    private val http: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val HOST = "microservices.mym.awsmpsa.com"
        private const val APP_VERSION = "1.55.0"
        private const val SITE_CODE = "AC_ES_ESP"
        private const val BRAND = "AC"
        private const val CULTURE = "es_ES"
        private const val LANGUAGE = "es"
    }

    suspend fun probe(oauthAccessToken: String): MicroservicesProbeResult = withContext(Dispatchers.IO) {
        require(oauthAccessToken.isNotBlank()) { "OAuth access token ausente" }

        var sessionV2 = "No probado"
        var sessionV1 = "No probado"
        var mymToken: String? = null

        fun sessionUrl(version: String) = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments("session/$version/accesstoken")
            .addQueryParameter("source", "APP")
            .addQueryParameter("v", APP_VERSION)
            .addQueryParameter("site_code", SITE_CODE)
            .addQueryParameter("language", LANGUAGE)
            .addQueryParameter("brand", BRAND)
            .addQueryParameter("culture", CULTURE)
            .build()

        fun safeSummary(code: Int, raw: String): String {
            if (raw.isBlank()) return "HTTP $code · cuerpo vacío"
            return try {
                val element = Json.parseToJsonElement(raw)
                val obj = element as? JsonObject
                val keys = obj?.keys?.sorted()?.take(16)?.joinToString(",")
                val status = obj?.get("returnCode")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("status")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                buildString {
                    append("HTTP ")
                    append(code)
                    if (!status.isNullOrBlank()) {
                        append(" · ")
                        append(status.take(100))
                    }
                    if (!keys.isNullOrBlank()) {
                        append(" · keys=[")
                        append(keys)
                        append("]")
                    }
                }
            } catch (_: Exception) {
                "HTTP $code · respuesta no JSON"
            }
        }

        fun findToken(element: JsonElement?): String? {
            when (element) {
                is JsonObject -> {
                    val preferred = listOf("mym_access_token", "mymAccessToken", "accessToken", "access_token")
                    for (key in preferred) {
                        val value = element[key]?.jsonPrimitive?.contentOrNull
                        if (!value.isNullOrBlank() && value.length > 20) return value
                    }
                    element.values.forEach { child ->
                        val found = findToken(child)
                        if (!found.isNullOrBlank()) return found
                    }
                }
                is JsonArray -> element.forEach { child ->
                    val found = findToken(child)
                    if (!found.isNullOrBlank()) return found
                }
                else -> Unit
            }
            return null
        }

        fun runSession(version: String): Pair<String, String?> {
            val request = Request.Builder()
                .url(sessionUrl(version))
                .header("ticket", oauthAccessToken)
                .header("Accept", "*/*")
                .header("Accept-Language", "es-ES")
                .header("User-Agent", "MyCitroen/$APP_VERSION (com.psa.mym.mycitroen; Android)")
                .get()
                .build()

            return try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val token = try { findToken(Json.parseToJsonElement(raw)) } catch (_: Exception) { null }
                    safeSummary(response.code, raw) to token
                }
            } catch (e: javax.net.ssl.SSLException) {
                "TLS · " + (e.message?.take(120) ?: e::class.java.simpleName) to null
            } catch (e: Exception) {
                "Red · " + e::class.java.simpleName to null
            }
        }

        val v2 = runSession("v2")
        sessionV2 = v2.first
        mymToken = v2.second

        if (mymToken.isNullOrBlank()) {
            val v1 = runSession("v1")
            sessionV1 = v1.first
            mymToken = v1.second
        } else {
            sessionV1 = "Omitido: v2 obtuvo sesión"
        }

        fun probeMym(path: String): String {
            val token = mymToken ?: return "No probado: sin mym-access-token"
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegments(path)
                .addQueryParameter("source", "APP")
                .addQueryParameter("v", APP_VERSION)
                .addQueryParameter("site_code", SITE_CODE)
                .addQueryParameter("language", LANGUAGE)
                .addQueryParameter("brand", BRAND)
                .addQueryParameter("culture", CULTURE)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("mym-access-token", token)
                .header("refresh-sams-cache", "1")
                .header("Accept", "*/*")
                .header("Accept-Language", "es-ES")
                .header("User-Agent", "MyCitroen/$APP_VERSION (com.psa.mym.mycitroen; Android)")
                .get()
                .build()
            return try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (path.endsWith("get_devices") && response.isSuccessful) {
                        try {
                            val root = Json.parseToJsonElement(raw)
                            val count = when (root) {
                                is JsonArray -> root.size
                                is JsonObject -> {
                                    val candidates = listOf("devices", "vehicles", "cars")
                                    candidates.firstNotNullOfOrNull { key -> (root[key] as? JsonArray)?.size }
                                }
                                else -> null
                            }
                            safeSummary(response.code, raw) + (count?.let { " · elementos=$it" } ?: "")
                        } catch (_: Exception) {
                            safeSummary(response.code, raw)
                        }
                    } else {
                        safeSummary(response.code, raw)
                    }
                }
            } catch (e: javax.net.ssl.SSLException) {
                "TLS · " + (e.message?.take(120) ?: e::class.java.simpleName)
            } catch (e: Exception) {
                "Red · " + e::class.java.simpleName
            }
        }

        val user = probeMym("me/v1/user")
        val devices = probeMym("me/v1/get_devices")

        MicroservicesProbeResult(
            sessionV2 = sessionV2,
            sessionV1 = sessionV1,
            user = user,
            devices = devices,
            tokenObtained = !mymToken.isNullOrBlank(),
            note = if (!mymToken.isNullOrBlank()) {
                "Sesión de app obtenida en memoria; el token no se muestra ni se persiste."
            } else {
                "No se obtuvo sesión de app. Solo se muestran códigos HTTP y forma segura de la respuesta."
            }
        )
    }
}
