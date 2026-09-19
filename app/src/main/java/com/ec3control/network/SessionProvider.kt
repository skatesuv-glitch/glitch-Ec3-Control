package com.ec3control.network

import com.ec3control.auth.AuthGateway
import com.ec3control.auth.AuthSession

class SessionProvider(private val auth: AuthGateway) {
    suspend fun validSession(): AuthSession {
        val current = auth.restoreSession() ?: error("Authentication required")
        if (!current.isExpired()) return current
        return auth.refresh(current).getOrThrow()
    }
}
