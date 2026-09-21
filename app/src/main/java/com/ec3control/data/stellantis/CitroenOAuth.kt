package com.ec3control.data.stellantis

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/**
 * Experimental MyCitroen ES OAuth adapter.
 * Mobile-app credentials are supplied at runtime/build time and are never logged.
 */
data class CitroenOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val oauthBaseUrl: String = "https://idpcvs.citroen.com/am/oauth2",
    val realm: String = "clientsB2CCitroen",
    val locale: String = "es-ES",
    val redirectUri: String = "mymacsdk://oauth2redirect/es"
)

data class CitroenTokens(val accessToken: String, val refreshToken: String?)

class CitroenOAuth(
    private val config: CitroenOAuthConfig,
    private val http: OkHttpClient = OkHttpClient()
) {
    fun authorizationUrl(): String = Uri.parse(config.oauthBaseUrl + "/authorize").buildUpon()
        .appendQueryParameter("client_id", config.clientId)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", config.redirectUri)
        .appendQueryParameter("scope", "openid profile email")
        .appendQueryParameter("locale", config.locale)
        .build().toString()

    suspend fun refresh(refreshToken: String): CitroenTokens = withContext(Dispatchers.IO) {
        require(refreshToken.isNotBlank()) { "OAuth refresh token unavailable" }
        val basic = Base64.getEncoder().encodeToString(
            (config.clientId + ":" + config.clientSecret).toByteArray(Charsets.UTF_8)
        )
        val tokenUrl = Uri.parse(config.oauthBaseUrl + "/access_token").buildUpon()
            .appendQueryParameter("grant_type", "refresh_token")
            .appendQueryParameter("refresh_token", refreshToken)
            .build()
            .toString()
        val request = Request.Builder()
            .url(tokenUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            require(response.isSuccessful) { "OAuth refresh HTTP " + response.code }
            val json = Json.parseToJsonElement(raw).jsonObject
            CitroenTokens(
                accessToken = json["access_token"]?.jsonPrimitive?.content
                    ?: error("OAuth refresh sin access_token"),
                refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
            )
        }
    }

    suspend fun exchangeCode(code: String): CitroenTokens = withContext(Dispatchers.IO) {
        val basic = Base64.getEncoder().encodeToString(
            (config.clientId + ":" + config.clientSecret).toByteArray(Charsets.UTF_8)
        )
        // Match the current community implementation exactly: token parameters
        // are query parameters on a body-less POST, not form fields in the body.
        val tokenUrl = Uri.parse(config.oauthBaseUrl + "/access_token").buildUpon()
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("grant_type", "authorization_code")
            .appendQueryParameter("code", code)
            .build()
            .toString()
        val request = Request.Builder()
            .url(tokenUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            require(response.isSuccessful) { "OAuth HTTP " + response.code }
            val json = Json.parseToJsonElement(raw).jsonObject
            CitroenTokens(
                accessToken = json["access_token"]?.jsonPrimitive?.content
                    ?: error("OAuth sin access_token"),
                refreshToken = json["refresh_token"]?.jsonPrimitive?.content
            )
        }
    }
}
