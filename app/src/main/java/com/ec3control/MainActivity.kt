package com.ec3control

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.ec3control.ui.Ec3App
import com.ec3control.ui.theme.Ec3Theme

class MainActivity : ComponentActivity() {
    private val oauthResult = mutableStateOf<OAuthCallbackResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeOAuthCallback(intent?.data)
        setContent {
            Ec3Theme {
                Ec3App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOAuthCallback(intent.data)
    }

    private fun consumeOAuthCallback(uri: Uri?) {
        if (uri?.scheme != "ec3control" || uri.host != "oauth") return

        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        oauthResult.value = when {
            !error.isNullOrBlank() -> OAuthCallbackResult.Error(error)
            !code.isNullOrBlank() -> OAuthCallbackResult.AuthorizationCodeReceived
            else -> OAuthCallbackResult.Error("Callback OAuth sin code ni error")
        }

        // Deliberately do not log or persist the authorization code.
    }
}

sealed interface OAuthCallbackResult {
    data object AuthorizationCodeReceived : OAuthCallbackResult
    data class Error(val reason: String) : OAuthCallbackResult
}
