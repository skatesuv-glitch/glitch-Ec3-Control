package com.ec3control.data.stellantis

/**
 * Public, non-secret Citroen/Stellantis routing metadata.
 * Client credentials are deliberately NOT stored here.
 */
object CitroenEnvironment {
    const val API_BASE_URL = "https://api.groupe-psa.com"
    const val OAUTH_BASE_URL = "https://idpcvs.citroen.com"
    const val REALM = "clientsB2CCitroen"
    const val LOCALE = "es-ES"

    fun statusPath(vehicleId: String, clientId: String): String =
        "/connectedcar/v4/user/vehicles/$vehicleId/status?client_id=$clientId&locale=$LOCALE"

    fun remoteAccessTokenPath(clientId: String): String =
        "/connectedcar/v4/virtualkey/remoteaccess/token?client_id=$clientId&locale=$LOCALE"
}
