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
    private val oauthCode = mutableStateOf<String?>(null)
    private val oauthError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeOAuthCallback(intent?.data)
        setContent {
            Ec3Theme {
                Ec3App(
                    oauthCode = oauthCode.value,
                    oauthError = oauthError.value,
                    clearOAuthResult = { oauthCode.value = null; oauthError.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOAuthCallback(intent.data)
    }

    private fun consumeOAuthCallback(uri: Uri?) {
        if (uri == null) return
        val accepted = (uri.scheme == "ec3control" && uri.host == "oauth") ||
            (uri.scheme == "mymacsdk" && uri.host == "oauth2redirect" && uri.path?.startsWith("/es") == true)
        if (!accepted) return
        oauthError.value = uri.getQueryParameter("error")
        oauthCode.value = uri.getQueryParameter("code")
        if (oauthCode.value.isNullOrBlank() && oauthError.value.isNullOrBlank()) {
            oauthError.value = "Citroën no devolvió código OAuth"
        }
        // Never log or persist authorization codes.
    }
}
