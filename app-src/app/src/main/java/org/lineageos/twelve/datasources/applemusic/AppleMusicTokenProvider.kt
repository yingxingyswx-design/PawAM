/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Supplies the Apple Music **developer token** (a JWT) required on every catalog request.
 *
 * Apple issues developer tokens to Apple Developer Program members. Two sources are supported,
 * in this order:
 *
 * 1. **User supplied** — a token the user generated themselves. Always preferred, because it is
 *    the only path that does not impersonate Apple's own web player.
 * 2. **Scraped** — the token embedded in the `music.apple.com` web player bundle. This is what
 *    the web player itself uses; it is short lived and may be absent or rotated at any time, so
 *    it is a convenience fallback, not a guarantee. When it is unavailable the catalog simply
 *    stays unauthenticated and the UI must ask the user for a token.
 *
 * Tokens are cached in memory with an expiry check so a stale token is not reused.
 *
 * @param manualToken Token configured by the user, if any.
 */
class AppleMusicTokenProvider(
    private val context: Context,
    private val manualToken: () -> String?,
) {
    private val mutex = Mutex()

    private var scrapedToken: String? = null
    private var scrapedAtMs: Long = 0L

    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get a usable developer token, or null when none could be obtained.
     *
     * @param force Skip caches and re-resolve.
     */
    suspend fun developerToken(force: Boolean = false): String? {
        manualToken()?.takeIf { it.isNotBlank() }?.let { token ->
            // A manual token is authoritative even when it looks expired: only the user can
            // replace it, and refusing to send it would hide the real error from them.
            return token
        }

        if (!force) {
            // A previously scraped token survives restarts; re-scraping costs a ~5 MB download.
            readCachedToken()?.takeIf { !isExpired(it) }?.let {
                Log.d(LOG_TAG, "Using persisted developer token")
                return it
            }
        }

        if (!force && isFresh(scrapedToken)) {
            return scrapedToken
        }

        return mutex.withLock {
            if (!force && isFresh(scrapedToken)) {
                return@withLock scrapedToken
            }

            val scraped = scrape()
            if (scraped != null) {
                Log.d(LOG_TAG, "Scraped a developer token (len=${scraped.length})")
                scrapedToken = scraped
                scrapedAtMs = System.currentTimeMillis()
                writeCachedToken(scraped)
                scraped
            } else {
                Log.w(LOG_TAG, "Failed to scrape a developer token")
                // Keep serving a previously scraped token while it is still valid.
                scrapedToken?.takeIf { !isExpired(it) }
            }
        }
    }

    private fun readCachedToken(): String? =
        preferences.getString(KEY_CACHED_TOKEN, null)?.takeIf { it.isNotBlank() }

    private fun writeCachedToken(token: String) {
        preferences.edit().putString(KEY_CACHED_TOKEN, token).apply()
    }

    private fun isFresh(token: String?) = token != null &&
        System.currentTimeMillis() - scrapedAtMs < SCRAPE_CACHE_TTL_MS &&
        !isExpired(token)

    private suspend fun scrape(): String? = runCatching {
        val html = fetchString(WEB_PLAYER_URL)
        if (html == null) {
            Log.w(LOG_TAG, "Scrape: could not fetch the web player page")
            return@runCatching null
        }
        Log.d(LOG_TAG, "Scrape: page length=${html.length}")

        val bundlePath = BUNDLE_PATTERN.find(html)?.value
        if (bundlePath == null) {
            Log.w(
                LOG_TAG,
                "Scrape: no bundle in page, head=${html.take(160).replace('\n', ' ')}",
            )
            return@runCatching null
        }

        val bundle = fetchString(WEB_PLAYER_URL + bundlePath)
        if (bundle == null) {
            Log.w(LOG_TAG, "Scrape: could not fetch bundle $bundlePath")
            return@runCatching null
        }
        Log.d(LOG_TAG, "Scrape: bundle=$bundlePath length=${bundle.length}")

        // `value`, not `groupValues[1]`: this pattern has no capture group, and indexing a
        // missing group throws IndexOutOfBoundsException.
        val candidates = JWT_PATTERN.findAll(bundle)
            .map { it.value }
            .distinct()
            .toList()

        Log.d(LOG_TAG, "Scrape: ${candidates.size} jwt candidates")

        // Prefer the web player's own token; fall back to any unexpired one.
        candidates.firstOrNull { !isExpired(it) && payloadOf(it)?.contains("AMPWebPlay") == true }
            ?: candidates.firstOrNull { !isExpired(it) }
    }.getOrElse { throwable ->
        Log.w(LOG_TAG, "Scrape failed", throwable)
        null
    }

    private suspend fun fetchString(url: String): String? {
        val client = AppleMusicHttp.client
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Origin", ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                response.body?.string().orEmpty().takeIf { response.isSuccessful }
            }
        }.getOrNull()
    }

    private fun payloadOf(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        )
    }.getOrNull()

    /**
     * Whether [jwt] is expired (or expires within a minute).
     *
     * The signature is deliberately not verified: this only decides whether to keep using a token
     * locally. Apple remains the authority on validity.
     */
    fun isExpired(jwt: String): Boolean = runCatching {
        val payload = payloadOf(jwt) ?: return false
        val expiresAt = JSONObject(payload).optLong("exp", 0L) * 1000L
        expiresAt == 0L || expiresAt < System.currentTimeMillis() + 60_000L
    }.getOrDefault(false)

    private companion object {
        private val LOG_TAG = "AppleMusicToken"

        private const val PREFERENCES_NAME = "apple_music_token"
        private const val KEY_CACHED_TOKEN = "developer_token"

        const val WEB_PLAYER_URL = "https://music.apple.com"
        const val ORIGIN = "https://music.apple.com"

        /** Scraped tokens are re-fetched at most once per 12 hours. */
        const val SCRAPE_CACHE_TTL_MS = 12L * 60L * 60L * 1000L

        /** The web player's entry bundle: `assets/index~<hash>.js`. */
        val BUNDLE_PATTERN = Regex("""/assets/index[~-][^/"]+\.js""")

        /** JWT shape. */
        val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+""")
    }
}
