/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import android.util.Base64
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
    private val manualToken: () -> String?,
) {
    private val mutex = Mutex()

    private var scrapedToken: String? = null
    private var scrapedAtMs: Long = 0L

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

        if (!force && isFresh(scrapedToken)) {
            return scrapedToken
        }

        return mutex.withLock {
            if (!force && isFresh(scrapedToken)) {
                return@withLock scrapedToken
            }

            val scraped = scrape()
            if (scraped != null) {
                scrapedToken = scraped
                scrapedAtMs = System.currentTimeMillis()
                scraped
            } else {
                // Keep serving a previously scraped token while it is still valid.
                scrapedToken?.takeIf { !isExpired(it) }
            }
        }
    }

    private fun isFresh(token: String?) = token != null &&
        System.currentTimeMillis() - scrapedAtMs < SCRAPE_CACHE_TTL_MS &&
        !isExpired(token)

    private suspend fun scrape(): String? = runCatching {
        val html = fetchString(WEB_PLAYER_URL) ?: return@runCatching null
        val bundlePath = BUNDLE_PATTERN.find(html)?.value ?: return@runCatching null
        val bundle = fetchString(WEB_PLAYER_URL + bundlePath) ?: return@runCatching null

        val candidates = JWT_PATTERN.findAll(bundle)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Prefer the web player's own token; fall back to any unexpired one.
        candidates.firstOrNull { !isExpired(it) && payloadOf(it)?.contains("AMPWebPlay") == true }
            ?: candidates.firstOrNull { !isExpired(it) }
    }.getOrNull()

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
