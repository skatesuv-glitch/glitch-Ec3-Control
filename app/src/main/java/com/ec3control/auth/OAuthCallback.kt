package com.ec3control.auth

data class OAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?
) {
    val isSuccess: Boolean get() = !code.isNullOrBlank() && error.isNullOrBlank()
}
