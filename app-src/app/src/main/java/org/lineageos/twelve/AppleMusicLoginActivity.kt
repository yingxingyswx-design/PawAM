/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lineageos.twelve.datasources.AppleMusicDataSource
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType

/**
 * Apple Music sign-in, driven by the official **MusicKit JS**.
 *
 * Apple's supported way for a third-party app to obtain a Music User Token is to run MusicKit in a
 * web context and call `music.authorize()`. PawAM hosts the bundled
 * `assets/musickit_auth.html` page in a [WebView] and receives the resulting token over a
 * JavaScript bridge.
 *
 * Deliberate non-goals:
 * - **No cookie harvesting.** Reading `media-user-token` out of the `music.apple.com` cookie jar
 *   bypasses Apple's authorization flow, breaks whenever Apple changes its web player, and gives
 *   the user no way to revoke access. MusicKit is used instead.
 * - **No credentials are logged.** The token is written straight to the provider row.
 *
 * The developer token (a JWT issued to the app) is required to configure MusicKit and is supplied
 * by the user in the provider settings; without it this screen explains what is missing instead of
 * failing silently.
 */
class AppleMusicLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var linearProgressIndicator: com.google.android.material.progressindicator.LinearProgressIndicator

    private val providerIdentifier by lazy {
        ProviderIdentifier(
            ProviderType.APPLE_MUSIC,
            intent.getLongExtra(EXTRA_PROVIDER_TYPE_ID, -1L),
        )
    }

    private val providersRepository by lazy {
        (application as TwelveApplication).providersRepository
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apple_music_login)

        webView = findViewById(R.id.webView)
        linearProgressIndicator = findViewById(R.id.linearProgressIndicator)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // MusicKit needs its own storage to hold the authorization between page loads.
            databaseEnabled = true
        }

        webView.addJavascriptInterface(Bridge(), JS_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Keep every navigation inside the WebView: the MusicKit flow relies on staying on
                // the same origin, and bouncing out to a browser would lose the bridge.
                return !request.url.toString().startsWith("https://")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                linearProgressIndicator.isVisible = false
            }
        }

        loadMusicKitPage()
    }

    /**
     * Load the bundled sign-in page with the configured developer token substituted in.
     *
     * The page is loaded through [WebView.loadDataWithBaseURL] so it keeps a real HTTPS origin
     * (`https://music.apple.com`), which MusicKit requires for `authorize()` to work.
     */
    private fun loadMusicKitPage() {
        linearProgressIndicator.isVisible = true

        lifecycleScope.launch {
            val developerToken = providersRepository
                .providerArguments(providerIdentifier)
                .first()
                ?.getString(AppleMusicDataSource.ARG_DEVELOPER_TOKEN.key)
                .orEmpty()

            if (developerToken.isBlank()) {
                // Surfacing this here is more useful than an opaque MusicKit error later.
                webView.loadDataWithBaseURL(
                    MUSICKIT_ORIGIN,
                    missingTokenPage(),
                    "text/html",
                    "utf-8",
                    null,
                )
                linearProgressIndicator.isVisible = false
                return@launch
            }

            val html = assets.open(ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
                .replace(DEVELOPER_TOKEN_PLACEHOLDER, developerToken)

            webView.loadDataWithBaseURL(MUSICKIT_ORIGIN, html, "text/html", "utf-8", null)
        }
    }

    private fun missingTokenPage() = """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
        <body style="font-family: sans-serif; padding: 32px; background:#101014; color:#e6e1e5;">
        <h1 style="font-size:1.2rem;">Developer token required</h1>
        <p style="line-height:1.6;color:#cac4d0;">
        MusicKit needs a developer token issued to an Apple Developer account.
        Open this provider's settings and paste a token, then try again.
        </p>
        </body></html>
    """.trimIndent()

    /**
     * Bridge exposed to the MusicKit page as `window.PawAM`.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String) {
            if (token.isBlank()) {
                return
            }

            runOnUiThread {
                lifecycleScope.launch {
                    runCatching {
                        providersRepository.updateAppleMusicMusicUserToken(
                            providerIdentifier,
                            token,
                        )
                    }.onSuccess {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            // Errors stay on the page; the user can retry without losing the session.
        }

        @JavascriptInterface
        fun onSignedOut() {
            runOnUiThread {
                lifecycleScope.launch {
                    runCatching {
                        providersRepository.updateAppleMusicMusicUserToken(
                            providerIdentifier,
                            "",
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun onMissingDeveloperToken() {
            // The page already renders the explanation.
        }
    }

    companion object {
        private const val EXTRA_PROVIDER_TYPE_ID = "provider_type_id"
        private const val ASSET_NAME = "musickit_auth.html"
        private const val DEVELOPER_TOKEN_PLACEHOLDER = "__DEVELOPER_TOKEN__"
        private const val JS_BRIDGE_NAME = "PawAM"

        /**
         * MusicKit requires a real origin; `loadDataWithBaseURL` needs one that looks like HTTPS.
         */
        private const val MUSICKIT_ORIGIN = "https://music.apple.com/"

        fun createIntent(context: Context, providerIdentifier: ProviderIdentifier) =
            Intent(context, AppleMusicLoginActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_TYPE_ID, providerIdentifier.typeId)
            }
    }
}
