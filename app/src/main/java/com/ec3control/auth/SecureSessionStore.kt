package com.ec3control.auth

/**
 * Contract for encrypted device-local session persistence.
 * The Android implementation must use platform-backed secure storage.
 * Never commit tokens, passwords, PINs or VINs to source control.
 */
interface SecureSessionStore {
    suspend fun read(): AuthSession?
    suspend fun write(session: AuthSession)
    suspend fun clear()
}
