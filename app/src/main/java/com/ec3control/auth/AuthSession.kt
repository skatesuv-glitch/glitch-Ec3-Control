package com.ec3control.auth

data class AuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAtEpochMillis
}
