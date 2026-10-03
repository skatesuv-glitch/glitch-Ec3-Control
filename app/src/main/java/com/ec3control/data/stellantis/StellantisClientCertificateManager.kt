package com.ec3control.data.stellantis

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Uses Android KeyChain for a user-owned/authorized client certificate.
 * The private key never leaves Android KeyChain and is never stored by EC3-Control.
 */
class StellantisClientCertificateManager(
    private val context: Context
) {
    companion object {
        const val API_CERT_HOST = "api-cert.groupe-psa.com"
        const val API_PUBLIC_HOST = "api.groupe-psa.com"
        private const val PREFS = "stellantis_mtls"
        private const val KEY_ALIAS = "client_cert_alias"
        private const val KEY_CONNECTED_CAR_CLIENT_ID = "connected_car_client_id"
    }

    data class ClientSelection(
        val client: OkHttpClient,
        val mtlsActive: Boolean,
        val status: String
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedAlias(): String? = prefs.getString(KEY_ALIAS, null)?.takeIf { it.isNotBlank() }

    fun connectedCarClientId(): String =
        prefs.getString(KEY_CONNECTED_CAR_CLIENT_ID, "").orEmpty().trim()

    fun saveConnectedCarClientId(value: String) {
        prefs.edit().putString(KEY_CONNECTED_CAR_CLIENT_ID, value.trim()).apply()
    }

    fun clearCertificate() {
        prefs.edit().remove(KEY_ALIAS).apply()
    }

    fun chooseCertificate(activity: Activity, onResult: (String?) -> Unit) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            KeyChainAliasCallback { alias ->
                if (!alias.isNullOrBlank()) {
                    prefs.edit().putString(KEY_ALIAS, alias).apply()
                }
                activity.runOnUiThread { onResult(alias) }
            },
            arrayOf("RSA", "EC"),
            null,
            API_CERT_HOST,
            443,
            savedAlias()
        )
    }

    suspend fun certificateStatus(alias: String? = savedAlias()): String = withContext(Dispatchers.IO) {
        if (alias.isNullOrBlank()) return@withContext "Ausente"
        try {
            val key = KeyChain.getPrivateKey(context, alias)
            val chain = KeyChain.getCertificateChain(context, alias)
            when {
                key == null -> "Alias seleccionado · clave no disponible"
                chain.isNullOrEmpty() -> "Alias seleccionado · certificado no disponible"
                else -> {
                    val leaf = chain.first()
                    try {
                        leaf.checkValidity()
                        "Presente ✓ · ${key.algorithm} · vigente"
                    } catch (_: Exception) {
                        "Presente · ${key.algorithm} · revisar vigencia"
                    }
                }
            }
        } catch (e: Exception) {
            "No accesible · ${e::class.java.simpleName}"
        }
    }

    suspend fun buildSelectedClient(): ClientSelection = withContext(Dispatchers.IO) {
        val alias = savedAlias()
        if (alias.isNullOrBlank()) {
            return@withContext ClientSelection(
                client = OkHttpClient(),
                mtlsActive = false,
                status = "Sin certificado cliente"
            )
        }

        try {
            val privateKey = KeyChain.getPrivateKey(context, alias)
                ?: return@withContext ClientSelection(OkHttpClient(), false, "Clave privada no disponible")
            val certificateChain = KeyChain.getCertificateChain(context, alias)
                ?.map { it as X509Certificate }
                ?.toTypedArray()
                ?: return@withContext ClientSelection(OkHttpClient(), false, "Cadena de certificado no disponible")

            val keyManager = fixedAliasKeyManager(alias, privateKey, certificateChain)
            val trustManager = defaultTrustManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(arrayOf<KeyManager>(keyManager), arrayOf(trustManager), null)

            ClientSelection(
                client = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .build(),
                mtlsActive = true,
                status = "mTLS preparado ✓"
            )
        } catch (e: Exception) {
            ClientSelection(
                client = OkHttpClient(),
                mtlsActive = false,
                status = "mTLS no disponible · ${e::class.java.simpleName}"
            )
        }
    }

    private fun fixedAliasKeyManager(
        alias: String,
        privateKey: PrivateKey,
        chain: Array<X509Certificate>
    ): X509KeyManager = object : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(alias)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = null

        override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
            if (requestedAlias == alias) chain else null

        override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
            if (requestedAlias == alias) privateKey else null
    }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
