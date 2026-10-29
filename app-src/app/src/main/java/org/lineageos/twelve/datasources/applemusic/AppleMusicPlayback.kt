/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-length playback lease for Apple Music.
 *
 * ## Why this is separate from the catalog client
 *
 * Metadata and audio are authorised by **different** mechanisms. Catalog reads need only a
 * developer token; a full-length stream needs the user's subscription, which Apple grants by
 * issuing a short-lived playback lease bound to the signed-in account. Requesting audio without
 * that lease yields either nothing or a 30 second preview.
 *
 * ## What the lease returns
 *
 * The response carries one or more assets, each an HLS manifest URL with a `flavor` code. Apple
 * commonly serves the full-length variant as **cbcs/ISO-23001-7 encrypted HLS** keyed to
 * Widevine, which a plain ExoPlayer cannot play: the manifest's key line uses Apple's web
 * dialect and the key itself must be requested from Apple's licence service.
 *
 * [Lease] therefore reports whether the stream is encrypted so callers can decide between
 * "play it" and "this needs a DRM session", instead of handing ExoPlayer a stream it will fail on.
 */
class AppleMusicPlayback(
    private val tokenProvider: AppleMusicTokenProvider,
    private val credentials: () -> AppleMusicCredentials,
) {
    /**
     * A resolved playback asset.
     *
     * @param url HLS manifest URL.
     * @param flavor Apple's asset flavour code, e.g. `28:ctrp256`.
     * @param isEncrypted Whether the manifest declares ISO-23001-7 (cbcs) encryption.
     * @param isHls Whether the response actually looked like an HLS manifest.
     */
    data class Lease(
        val url: String,
        val flavor: String?,
        val isEncrypted: Boolean,
        val isHls: Boolean,
        /** Apple's licence endpoint for this track (`hls-key-server-url`). */
        val licenseUrl: String? = null,
        /** Original `#EXT-X-KEY` data URI, echoed verbatim in the licence request. */
        val keyUri: String? = null,
        /** Apple's Widevine provisioning endpoint (`widevine-cert-url`), if offered. */
        val certUrl: String? = null,
    )

    /**
     * Request a lease for [songId] and probe the resulting manifest.
     *
     * @return The preferred asset, or null when Apple refused (no subscription, region mismatch,
     *   track unavailable) or the request failed.
     */
    suspend fun lease(songId: String): Lease? = withContext(Dispatchers.IO) {
        val developerToken = tokenProvider.developerToken() ?: return@withContext null
        val userToken = credentials().musicUserToken.takeIf { it.isNotBlank() }
            ?: return@withContext null

        val body = JSONObject()
            .put("salableAdamId", songId)
            .put("language", "en-US")
            .toString()

        val request = Request.Builder()
            .url(WEB_PLAYBACK_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $developerToken")
            .header("Media-User-Token", userToken)
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)
            .build()

        val response = runCatching {
            AppleMusicHttp.client.newCall(request).execute().use { it.code to it.body?.string() }
        }.getOrNull() ?: run {
            Log.w(LOG_TAG, "Lease request failed for $songId")
            return@withContext null
        }

        val (code, payload) = response
        Log.d(LOG_TAG, "Lease for $songId -> HTTP $code")

        if (code != 200 || payload.isNullOrBlank()) {
            // Apple reports entitlement problems in the body (2002 expired credentials,
            // 3076 content unavailable, 3077 track not found); surface the code in logs.
            Log.w(LOG_TAG, "Lease refused: ${payload?.take(200)}")
            return@withContext null
        }

        // Diagnostic: surface the licence-relevant parts of the lease instead of the whole
        // document (which is ~80 KB and gets truncated by logcat).
        runCatching {
            val song = JSONObject(payload).optJSONArray("songList")?.optJSONObject(0)
            val keys = song?.keys()?.asSequence()?.toList().orEmpty()
            Log.d(LOG_TAG, "songList[0] keys: $keys")

            for (key in keys) {
                val value = song?.opt(key)?.toString().orEmpty()
                val interesting = key.contains("licen", ignoreCase = true) ||
                    key.contains("cert", ignoreCase = true) ||
                    key.contains("drm", ignoreCase = true) ||
                    key.contains("fairplay", ignoreCase = true) ||
                    value.contains("licen", ignoreCase = true) ||
                    value.contains("cert", ignoreCase = true) ||
                    value.contains("widevine", ignoreCase = true)

                if (interesting) {
                    Log.d(LOG_TAG, "  $key = ${value.take(300)}")
                }
            }

            val assets = song?.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                val first = assets.optJSONObject(0)
                Log.d(LOG_TAG, "asset[0]: ${first?.toString()?.take(600)}")
            }
        }.onFailure { Log.w(LOG_TAG, "lease introspection failed", it) }

        val songObject = runCatching {
            JSONObject(payload).optJSONArray("songList")?.optJSONObject(0)
        }.getOrNull()

        val licenseUrl = songObject?.optString("hls-key-server-url")
            ?.takeIf { it.isNotBlank() }

        val certUrl = songObject?.optString("widevine-cert-url")
            ?.takeIf { it.isNotBlank() }

        val assets = runCatching {
            JSONObject(payload)
                .optJSONArray("songList")
                ?.optJSONObject(0)
                ?.optJSONArray("assets")
        }.getOrNull()

        if (assets == null || assets.length() == 0) {
            Log.w(LOG_TAG, "Lease returned no assets")
            return@withContext null
        }

        val candidates = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .mapNotNull { asset ->
                val url = asset.optString("URL").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val flavor = asset.optString("flavor").takeIf { it.isNotBlank() }
                Triple(flavor, url, rankOf(flavor))
            }
            .sortedBy { it.third }

        Log.d(LOG_TAG, "Lease assets: ${candidates.map { it.first }}")

        val (flavor, url, _) = candidates.firstOrNull() ?: return@withContext null

        // Probe the manifest: the difference between a playable stream and one that needs a DRM
        // session is only visible here.
        val manifest = runCatching {
            AppleMusicHttp.client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Origin", AppleMusicHttp.ORIGIN)
                    .header("User-Agent", AppleMusicHttp.USER_AGENT)
                    .build()
            ).execute().use { it.body?.string() }
        }.getOrNull()

        val isHls = manifest?.trimStart()?.startsWith("#EXTM3U") == true
        val isEncrypted = manifest?.contains("ISO-23001-7") == true ||
            manifest?.contains("SAMPLE-AES") == true

        Log.d(
            LOG_TAG,
            "Manifest flavor=$flavor hls=$isHls encrypted=$isEncrypted " +
                "head=${manifest?.take(220)?.replace('\n', '|')}",
        )
        // Full manifest: the KEY line and any licence hints live here.
        Log.d(LOG_TAG, "MANIFEST: ${manifest?.take(3000)?.replace('\n', '|')}")

        val keyUri = manifest?.let { body ->
            body.lineSequence()
                .firstOrNull { it.startsWith("#EXT-X-KEY") }
                ?.let { org.lineageos.twelve.datasources.applemusic.drm.HlsManifestRewriter.extractUri(it) }
        }

        Lease(
            url = url,
            flavor = flavor,
            isEncrypted = isEncrypted,
            isHls = isHls,
            licenseUrl = licenseUrl,
            keyUri = keyUri,
            certUrl = certUrl,
        )
    }

    /**
     * Lower is better.
     *
     * `28:ctrp256` and `30:cbcp256` are the variants Apple's own web player uses for full tracks,
     * so they are preferred over anything lossless that is more likely to be refused.
     */
    private fun rankOf(flavor: String?): Int {
        if (flavor == null) return 99

        return when (flavor) {
            "28:ctrp256" -> 0
            "30:cbcp256" -> 1
            else -> {
                val lower = flavor.lowercase()
                when {
                    lower.contains("alac") || lower.contains("lossless") -> 3
                    lower.contains("256") -> 5
                    else -> 50
                }
            }
        }
    }

    private companion object {
        private val LOG_TAG = "AppleMusicLease"

        /** Apple's playback lease endpoint used by the web player. */
        private const val WEB_PLAYBACK_URL =
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
    }
}
