package com.ec3control.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HttpTransport(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun get(url: String, bearerToken: String): String = execute(Request.Builder().url(url).header("Authorization", "Bearer $bearerToken").get().build())
    suspend fun post(url: String, bearerToken: String, json: String): String = execute(Request.Builder().url(url).header("Authorization", "Bearer $bearerToken").header("Content-Type", "application/json").post(json.toRequestBody()).build())
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { httpResponse ->
            val body = httpResponse.body?.string().orEmpty()
            if (!httpResponse.isSuccessful) error("HTTP " + httpResponse.code)
            body
        }
    }
}
