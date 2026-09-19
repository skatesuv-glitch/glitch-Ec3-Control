package com.ec3control.auth

/**
 * Separate credential used for remote vehicle commands.
 * It must never be confused with the normal account/API access token.
 */
data class RemoteAccessSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAtEpochMillis
}

interface RemoteAccessGateway {
    suspend fun restore(): RemoteAccessSession?
    suspend fun activateWithOtp(pin: String): Result<RemoteAccessSession>
    suspend fun refresh(session: RemoteAccessSession): Result<RemoteAccessSession>
    suspend fun clear()
}
