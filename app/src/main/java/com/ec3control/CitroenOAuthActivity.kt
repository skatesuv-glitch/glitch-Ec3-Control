package com.ec3control

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Experimental local OAuth browser.
 * No JavaScript bridge, form inspection, credential logging or persistence is used.
 * The only value captured is the OAuth authorization code from the registered
 * MyCitroen redirect URI.
 */
class CitroenOAuthActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = intent.getStringExtra(EXTRA_URL)
        if (startUrl.isNullOrBlank()) {
            finish()
            return
        }

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.saveFormData = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleRedirect(request?.url)
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleRedirect(url?.let(Uri::parse))
            }
        }
        setContentView(web)
        web.loadUrl(startUrl)
    }

    private fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        val isCitroenCallback = uri.scheme.equals("mymacsdk", true) &&
            uri.host.equals("oauth2redirect", true)
        if (!isCitroenCallback) return false

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        // Ignore intermediate custom-scheme redirects until OAuth has a result.
        if (code.isNullOrBlank() && error.isNullOrBlank()) {
            return false
        }

        // Hand only the final OAuth callback to our existing parser.
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
        return true
    }

    companion object {
        const val EXTRA_URL = "oauth_url"
    }
}
