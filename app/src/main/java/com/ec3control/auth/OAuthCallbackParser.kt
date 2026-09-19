package com.ec3control.auth

import android.net.Uri

object OAuthCallbackParser {
    fun parse(uri: Uri): OAuthCallback = OAuthCallback(
        code = uri.getQueryParameter("code"),
        state = uri.getQueryParameter("state"),
        error = uri.getQueryParameter("error")
    )
}
