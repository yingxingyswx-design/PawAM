/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType

/**
 * Built-in browser used to sign in to a provider.
 *
 * The user logs in on the provider's **own** web page — PawAM never asks for a password and never
 * renders a lookalike form. Once the session cookie appears in the WebView's cookie jar, the
 * relevant credentials are extracted and stored on the provider row, then this screen closes.
 *
 * This is deliberately one screen for every provider: the only per-provider differences are the
 * start URL and which cookie keys matter, both captured in [ProviderSpec].
 *
 * Security:
 * - Credentials are session cookies. They are never logged.
 * - The **same** session is sent to the app's HTTP client afterwards, so the provider must be one
 *   whose web session is accepted by its API. This is true for NetEase; Apple Music additionally
 *   requires a developer token (configured separately) to authorise API calls.
 */
class WebLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var linearProgressIndicator: LinearProgressIndicator
    private lateinit var reloadMaterialButton: MaterialButton
    private lateinit var toolbar: MaterialToolbar

    private val providerType by lazy {
        runCatching {
            ProviderType.valueOf(intent.getStringExtra(EXTRA_PROVIDER_TYPE).orEmpty())
        }.getOrNull()
    }

    private val providerIdentifier by lazy {
        ProviderIdentifier(
            providerType ?: ProviderType.NETEASE,
            intent.getLongExtra(EXTRA_PROVIDER_TYPE_ID, -1L),
        )
    }

    private val spec by lazy { providerType?.let(ProviderSpec::of) }

    private val providersRepository by lazy {
        (application as TwelveApplication).providersRepository
    }

    /** Guards against storing the same session repeatedly while pages keep loading. */
    private var saved = false

    /** True while a candidate session is being validated, to avoid duplicate checks. */
    private var verifying = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)

        webView = findViewById(R.id.webView)
        linearProgressIndicator = findViewById(R.id.linearProgressIndicator)
        reloadMaterialButton = findViewById(R.id.reloadMaterialButton)
        toolbar = findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener { finish() }

        reloadMaterialButton.setOnClickListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val currentSpec = spec
        if (currentSpec == null || providerType == null) {
            finish()
            return
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Only override the UA when the provider asks for it: assigning null would reset the
            // WebView's identity and makes the page load fail.
            currentSpec.userAgent?.let { userAgentString = it }
        }

        // Both providers complete part of their login inside an iframe, so third-party cookies
        // must be accepted or the flow never finishes.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Keep every navigation inside this WebView: leaving it would drop the cookie jar
                // we are about to read.
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                linearProgressIndicator.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                linearProgressIndicator.isVisible = false
                toolbar.title = view.title?.takeIf { it.isNotBlank() } ?: currentSpec.title

                tryCaptureSession(currentSpec)
            }
        }

        webView.loadUrl(currentSpec.startUrl)
    }

    /**
     * Look for the provider's session cookie and persist it once it appears.
     *
     * Extraction happens after every page load because login may finish on any page (a redirect,
     * or a frame inside the login form).
     */
    private fun tryCaptureSession(currentSpec: ProviderSpec) {
        if (saved || verifying) {
            return
        }

        val cookieHeader = CookieManager.getInstance().getCookie(currentSpec.cookieUrl) ?: return
        val cookies = parseCookie(cookieHeader)

        val credentials = currentSpec.extract(cookies) ?: return

        verifying = true
        linearProgressIndicator.isVisible = true

        lifecycleScope.launch {
            val account = if (providerType == ProviderType.NETEASE) {
                runCatching {
                    org.lineageos.twelve.datasources.netease.NetEaseClient(cookieHeader).account()
                }.getOrNull()
            } else {
                null
            }

            // A cookie alone is not proof of sign-in: NetEase hands an anonymous MUSIC_U to
            // visitors. Without a resolvable account, stay on the page and let the user sign in.
            if (providerType == ProviderType.NETEASE && account == null) {
                verifying = false
                linearProgressIndicator.isVisible = false
                return@launch
            }

            runCatching {
                when (providerType) {
                    ProviderType.NETEASE -> providersRepository.updateNetEaseSession(
                        providerIdentifier = providerIdentifier,
                        cookie = cookieHeader,
                        userId = account?.userId,
                        nickname = account?.nickname,
                    )

                    ProviderType.APPLE_MUSIC -> {
                        providersRepository.updateAppleMusicMusicUserToken(
                            providerIdentifier = providerIdentifier,
                            musicUserToken = credentials.musicUserToken,
                        )

                        credentials.storefront?.let {
                            providersRepository.updateAppleMusicStorefront(
                                providerIdentifier = providerIdentifier,
                                storefront = it,
                            )
                        }
                    }

                    else -> Unit
                }
            }.onSuccess {
                saved = true
                setResult(RESULT_OK)
                finish()
            }.onFailure { throwable ->
                Log.e(LOG_TAG, "Failed to store the session", throwable)
                verifying = false
                linearProgressIndicator.isVisible = false
            }
        }
    }
    /**
     * What the built-in browser needs to know about a provider.
     *
     * @param title Toolbar fallback title.
     * @param startUrl The provider's own login page.
     * @param cookieUrl The URL whose cookie jar holds the session.
     * @param userAgent Optional UA override; some login pages behave differently otherwise.
     * @param extract Reads the credentials out of the cookie map, or null when not signed in yet.
     */
    private data class ProviderSpec(
        val title: String,
        val startUrl: String,
        val cookieUrl: String,
        val userAgent: String?,
        val extract: (Map<String, String>) -> Credentials?,
    ) {
        companion object {

            fun of(providerType: ProviderType): ProviderSpec? = when (providerType) {
                ProviderType.NETEASE -> ProviderSpec(
                    title = "NetEase",
                    startUrl = "https://music.163.com/#/login",
                    cookieUrl = "https://music.163.com",
                    // Use the WebView's default (mobile) user agent: the desktop site renders a
                    // fixed-width layout that overflows a phone screen.
                    userAgent = null,
                    extract = { cookies ->
                        cookies[NETEASE_SESSION_COOKIE]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Credentials(it, null) }
                    },
                )

                ProviderType.APPLE_MUSIC -> ProviderSpec(
                    title = "Apple Music",
                    startUrl = "https://music.apple.com/",
                    cookieUrl = "https://music.apple.com",
                    userAgent = null,
                    extract = { cookies ->
                        cookies[APPLE_USER_TOKEN_COOKIE]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { token ->
                                // `itua` carries the account's storefront code.
                                Credentials(
                                    musicUserToken = token,
                                    storefront = cookies[APPLE_STOREFRONT_COOKIE]
                                        ?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }
                                        ?.lowercase(),
                                )
                            }
                    },
                )

                else -> null
            }
        }
    }

    /**
     * Credentials recovered from the cookie jar.
     *
     * @param musicUserToken NetEase `MUSIC_U` or Apple `media-user-token`.
     * @param storefront Apple catalog region, when the cookie jar exposes it.
     */
    private data class Credentials(
        val musicUserToken: String,
        val storefront: String?,
    )

    companion object {
        private val LOG_TAG = WebLoginActivity::class.simpleName!!

        private const val EXTRA_PROVIDER_TYPE = "provider_type"
        private const val EXTRA_PROVIDER_TYPE_ID = "provider_type_id"

        private const val NETEASE_SESSION_COOKIE = "MUSIC_U"
        private const val APPLE_USER_TOKEN_COOKIE = "media-user-token"
        private const val APPLE_STOREFRONT_COOKIE = "itua"

        /**
         * Parse a `a=1; b=2` cookie header into a map.
         */
        fun parseCookie(raw: String): Map<String, String> = raw
            .split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    part.substring(0, separator).trim() to part.substring(separator + 1).trim()
                }
            }
            .toMap()

        fun createIntent(
            context: Context,
            providerIdentifier: ProviderIdentifier,
        ) = Intent(context, WebLoginActivity::class.java).apply {
            putExtra(EXTRA_PROVIDER_TYPE, providerIdentifier.type.name)
            putExtra(EXTRA_PROVIDER_TYPE_ID, providerIdentifier.typeId)
        }
    }
}
