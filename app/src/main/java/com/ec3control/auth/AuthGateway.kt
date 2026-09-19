package com.ec3control.auth

/**
 * Authentication boundary.
 * UI must never know provider secrets, passwords or raw OAuth implementation details.
 */
interface AuthGateway {
    suspend fun restoreSession(): AuthSession?
    suspend fun beginLogin(): LoginRequest
    suspend fun completeLogin(callbackUri: String): Result<AuthSession>
    suspend fun refresh(session: AuthSession): Result<AuthSession>
    suspend fun logout()
}

data class LoginRequest(val authorizationUri: String)
