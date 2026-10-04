package com.ec3control.data.stellantis

import android.content.Context
import com.ec3control.BuildConfig
import com.ec3control.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class CurrentApiProbeResult(
    val transportCertificate: String = "Pendiente",
    val sessionExchange: String = "Pendiente",
    val currentUser: String = "Pendiente",
    val devices: String = "Pendiente",
    val vehicle: String = "Pendiente",
    val message: String = "Sin ejecutar"
)

class StellantisCurrentApiProbe(
    private val context: Context,
    private val oauthAccessToken: String,
    private val publicHttp: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val MICRO_HOST = "microservices.mym.awsmpsa.com"
        private const val REALM = "clientsB2CCitroen"
        private const val PFX_PASSWORD = "y5Y2my5B"
    }

    suspend fun run(): CurrentApiProbeResult = withContext(Dispatchers.IO) {
        val vins = loadAssociatedVins()
        val transport = buildTransportClient()
        val tlsClient = transport.first
        val transportStatus = transport.second

        if (tlsClient == null) {
            return@withContext CurrentApiProbeResult(
                transportCertificate = transportStatus,
                message = "No se pudo preparar el transporte TLS de la app."
            )
        }

        val session = obtainCurrentSession(tlsClient)
        if (session.token.isNullOrBlank()) {
            return@withContext CurrentApiProbeResult(
                transportCertificate = transportStatus,
                sessionExchange = session.summary,
                message = "El transporte llegó al servicio actual, pero no se obtuvo una sesión MyM."
            )
        }

        val appParams = session.params
        val user = readCurrentResource(tlsClient, "/me/v1/user", session.token, appParams)
        val devices = readCurrentResource(tlsClient, "/me/v1/get_devices", session.token, appParams)
        val vehicle = if (vins.isNotEmpty()) {
            readCurrentResource(tlsClient, "/car/v1/vehicle/" + vins.first(), session.token, appParams)
        } else {
            "MAUV no devolvió VIN utilizable"
        }

        CurrentApiProbeResult(
            transportCertificate = transportStatus,
            sessionExchange = session.summary,
            currentUser = user,
            devices = devices,
            vehicle = vehicle,
            message = "Prueba CVS/MyM actual completada en solo lectura. Tokens, VIN e identificadores permanecen ocultos."
        )
    }

    private fun buildTransportClient(): Pair<OkHttpClient?, String> {
        return try {
            val password = PFX_PASSWORD.toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12")
            context.resources.openRawResource(R.raw.mwp).use { keyStore.load(it, password) }

            val aliases = keyStore.aliases()
            val alias = if (aliases.hasMoreElements()) aliases.nextElement() else null
            val cert = alias?.let { keyStore.getCertificate(it) as? X509Certificate }
            val certState = if (cert != null) {
                try {
                    cert.checkValidity()
                    "certificado transporte cargado ✓ · vigente"
                } catch (_: Exception) {
                    "certificado transporte cargado · revisar vigencia"
                }
            } else {
                "certificado transporte cargado"
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, arrayOf(trustManager), null)

            Pair(
                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .build(),
                certState
            )
        } catch (e: Exception) {
            Pair(null, "transporte TLS no disponible · " + e::class.java.simpleName)
        }
    }

    private fun loadAssociatedVins(): List<String> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.groupe-psa.com")
            .addPathSegments("applications/cvs/v4/mauv/car-associations")
            .addQueryParameter("client_id", BuildConfig.CITROEN_CLIENT_ID)
            .addQueryParameter("locale", "es-ES")
            .build()

        return try {
            publicHttp.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + oauthAccessToken)
                    .header("x-introspect-realm", REALM)
                    .header("x-transaction-id", "1234")
                    .header("Accept", "application/hal+json")
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val raw = response.body?.string().orEmpty()
                val array = Json.parseToJsonElement(raw) as? JsonArray ?: return@use emptyList()
                array.mapNotNull { row ->
                    row.jsonObject["vehicle"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.length == 17 }
                }.distinct()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class SessionResult(
        val token: String?,
        val params: AppParams,
        val summary: String
    )

    private data class AppParams(
        val siteCode: String,
        val language: String = "es",
        val brand: String = "AC",
        val culture: String = "es_ES"
    )

    private fun obtainCurrentSession(http: OkHttpClient): SessionResult {
        data class Variant(
            val name: String,
            val params: AppParams,
            val useTicketHeader: Boolean,
            val useBearerHeader: Boolean,
            val version: String? = null
        )

        val variants = listOf(
            Variant("ticket-ES", AppParams("AC_ES_ESP"), useTicketHeader = true, useBearerHeader = false),
            Variant("ticket-DE", AppParams("AC_DE_ESP"), useTicketHeader = true, useBearerHeader = false, version = "1.35.2"),
            Variant("bearer-ES", AppParams("AC_ES_ESP"), useTicketHeader = false, useBearerHeader = true)
        )

        val attempts = mutableListOf<String>()
        for (variant in variants) {
            val urlBuilder = HttpUrl.Builder()
                .scheme("https")
                .host(MICRO_HOST)
                .addPathSegments("session/v2/accesstoken")
                .addQueryParameter("source", "APP")
                .addQueryParameter("site_code", variant.params.siteCode)
                .addQueryParameter("language", variant.params.language)
                .addQueryParameter("brand", variant.params.brand)
                .addQueryParameter("culture", variant.params.culture)
            variant.version?.let { urlBuilder.addQueryParameter("v", it) }

            try {
                val requestBuilder = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Accept", "*/*")
                    .header("User-Agent", "okhttp/4.12.0")
                if (variant.useTicketHeader) requestBuilder.header("ticket", oauthAccessToken)
                if (variant.useBearerHeader) requestBuilder.header("Authorization", "Bearer " + oauthAccessToken)

                http.newCall(requestBuilder.get().build()).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val token = findSessionToken(raw)
                    attempts += variant.name + "=HTTP " + response.code +
                        " token=" + if (token.isNullOrBlank()) "no" else "sí" +
                        " shape=" + safeJsonShape(raw)
                    if (!token.isNullOrBlank()) {
                        return SessionResult(
                            token = token,
                            params = variant.params,
                            summary = attempts.joinToString(" ; ")
                        )
                    }
                }
            } catch (e: Exception) {
                attempts += variant.name + "=" + e::class.java.simpleName +
                    safeTlsSuffix(e.message)
            }
        }

        return SessionResult(
            token = null,
            params = variants.first().params,
            summary = attempts.joinToString(" ; ")
        )
    }

    private fun readCurrentResource(
        http: OkHttpClient,
        path: String,
        mymToken: String,
        params: AppParams
    ): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(MICRO_HOST)
            .addPathSegments(path.trimStart('/'))
            .addQueryParameter("source", "APP")
            .addQueryParameter("site_code", params.siteCode)
            .addQueryParameter("language", params.language)
            .addQueryParameter("brand", params.brand)
            .addQueryParameter("culture", params.culture)
            .build()

        return try {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                    .header("mym-access-token", mymToken)
                    .header("refresh-sams-cache", "1")
                    .header("User-Agent", "okhttp/4.12.0")
                    .get()
                    .build()
            ).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                "HTTP " + response.code + " · " + safeJsonShape(raw)
            }
        } catch (e: Exception) {
            e::class.java.simpleName + safeTlsSuffix(e.message)
        }
    }

    private fun findSessionToken(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val root = Json.parseToJsonElement(raw)
            fun search(element: JsonElement): String? {
                return when (element) {
                    is JsonObject -> {
                        val names = listOf(
                            "mym_access_token", "mym-access-token",
                            "access_token", "accessToken"
                        )
                        for (name in names) {
                            val candidate = element[name]?.jsonPrimitive?.contentOrNull
                            if (!candidate.isNullOrBlank() && candidate.length > 20) return candidate
                        }
                        element.values.firstNotNullOfOrNull { search(it) }
                    }
                    is JsonArray -> element.firstNotNullOfOrNull { search(it) }
                    else -> null
                }
            }
            search(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeJsonShape(raw: String): String {
        if (raw.isBlank()) return "body vacío"
        return try {
            val root = Json.parseToJsonElement(raw)
            when (root) {
                is JsonObject -> {
                    val top = root.keys.sorted().take(20)
                    val success = root["success"]
                    val nested = if (success is JsonObject) {
                        " successKeys=" + success.keys.sorted().take(20).joinToString(",")
                    } else if (success is JsonArray) {
                        " successArray=" + success.size
                    } else ""
                    "keys=" + top.joinToString(",") + nested
                }
                is JsonArray -> "array(" + root.size + ")"
                else -> "json scalar"
            }
        } catch (_: Exception) {
            val clean = raw
                .replace(Regex("[A-HJ-NPR-Z0-9]{17}"), "VIN17_oculto")
                .replace(Regex("[A-Za-z0-9_\\-.]{40,}"), "dato_oculto")
                .replace(Regex("\\s+"), " ")
                .take(180)
            if (clean.isBlank()) "body no JSON" else "texto=" + clean
        }
    }

    private fun safeTlsSuffix(message: String?): String {
        val m = message.orEmpty()
        return when {
            m.contains("ACCESS_DENIED", ignoreCase = true) -> " · TLS_ACCESS_DENIED"
            m.contains("certificate", ignoreCase = true) -> " · TLS_CERTIFICATE"
            m.contains("handshake", ignoreCase = true) -> " · TLS_HANDSHAKE"
            else -> ""
        }
    }
}
