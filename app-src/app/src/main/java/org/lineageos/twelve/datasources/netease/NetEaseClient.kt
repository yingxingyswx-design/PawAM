/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.netease

import android.content.Context
import android.util.Log
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal NetEase Cloud Music client.
 *
 * Two transports are in use, matching what the web/desktop clients do:
 * - **eapi**: AES-encrypted `params` POST, required by most endpoints (playlists, song URLs,
 *   account state). See [NetEaseCrypto].
 * - **plain GET**: a few read-only endpoints also accept unauthenticated GET requests, which is
 *   enough for anonymous catalog browsing.
 *
 * Authentication is a `MUSIC_U` cookie captured from a WebView login. It is stored per provider
 * row (see `NetEaseProvider`), never globally, and is sent verbatim on authenticated calls.
 *
 * @param cookie The raw cookie header value, or null for anonymous browsing.
 */
class NetEaseClient(
    private val cookie: String?,
    cache: Cache? = null,
) {
    private val httpClient = OkHttpClient.Builder()
        .apply { cache?.let { cache(it) } }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Whether an account cookie is present. Note this says nothing about its validity. */
    val isAuthenticated: Boolean get() = !cookie.isNullOrBlank()

    // ---------------------------------------------------------------------
    // eapi
    // ---------------------------------------------------------------------

    /**
     * Perform an eapi POST against [path] (which must start with `/api/`).
     *
     * @param useInterface Send through `interface.music.163.com` instead of `music.163.com`.
     *   Song URL lookups use the interface host because that is where the official clients ask.
     * @return The decoded JSON object, or null when the transport or decode failed.
     */
    suspend fun eapi(
        path: String,
        payload: JSONObject,
        useInterface: Boolean = false,
    ): JSONObject? = withContext(Dispatchers.IO) {
        // Device identity fields. The server treats requests without these as an unknown client
        // and silently caps audio quality, so always send them.
        payload.put("header", defaultHeader())
        // eapi requires `e_r` for some endpoints; harmless elsewhere.
        payload.putOpt("e_r", false)

        val host = if (useInterface) INTERFACE_HOST else WEB_HOST
        val url = host + "/eapi/" + path.removePrefix("/api/")
        val params = NetEaseCrypto.encryptParams(url, payload.toString())

        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("params", params).build())
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Cookie", cookieHeader())
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = body.toJsonObject()

                // Write endpoints may answer with an encrypted document.
                json ?: NetEaseCrypto.decryptResponse(body)?.toJsonObject()
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Plain GET
    // ---------------------------------------------------------------------

    /**
     * Perform a plain GET against [path] on [host].
     *
     * Anonymous-only fallback used for browsing endpoints that do not require a signature.
     */
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        host: String = WEB_HOST,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val url = (host + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: return@withContext null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Cookie", cookieHeader())
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { it.body?.string().orEmpty().toJsonObject() }
        }.getOrNull()
    }

    private fun cookieHeader() = cookie.orEmpty()

    /**
     * Client identity declared to the server.
     *
     * Values mirror a recent desktop client; a stable per-install `deviceId` matters because a
     * device id that changes per request is a strong "not a real client" signal.
     */
    private fun defaultHeader() = JSONObject()
        .put("os", "pc")
        .put("appver", APP_VERSION)
        .put("osver", OS_VERSION)
        .put("deviceId", deviceId)
        .put("requestId", (20_000_000..30_000_000).random().toString())

    private fun String.toJsonObject() = runCatching { JSONObject(this) }.getOrNull()

    /**
     * Resolve the signed-in account.
     *
     * Returns null when there is no session or it has expired. NetEase reports an expired session
     * as `code: 200` with `account: null`, so the presence of the account object — not the status
     * code — decides validity.
     */
    suspend fun account(): NetEaseAccount? {
        if (!isAuthenticated) {
            return null
        }

        val root = eapi(ACCOUNT_PATH, JSONObject()) ?: return null
        val account = root.optJSONObject("account") ?: return null
        val profile = root.optJSONObject("profile")

        return NetEaseAccount(
            userId = account.optLong("id", 0L).takeIf { it > 0L },
            nickname = profile?.optString("nickname")?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    /**
     * The user's own playlists, including the "liked music" pseudo-playlist (`specialType == 5`).
     */
    suspend fun userPlaylists(uid: Long, limit: Int = 100): List<NetEasePlaylist> {
        val root = eapi(
            USER_PLAYLIST_PATH,
            org.json.JSONObject()
                .put("uid", uid.toString())
                .put("limit", limit.toString())
                .put("offset", "0")
                .put("includeVideo", "false"),
        ) ?: return emptyList()

        return root.optJSONArray("playlist").objects().mapNotNull { it.playlist() }
    }

    /**
     * Albums the user has saved to their library.
     *
     * The library page shows these as a grid; an empty list is normal for accounts that only
     * collect songs.
     */
    suspend fun userAlbums(limit: Int = 100): List<NetEaseAlbum> {
        val root = eapi(
            ALBUM_SUBLIST_PATH,
            org.json.JSONObject()
                .put("limit", limit.toString())
                .put("offset", "0")
                .put("total", "true"),
        ) ?: return emptyList()

        return root.optJSONArray("data").objects().mapNotNull { it.album() }
    }

    /**
     * The daily recommendation shelf.
     */
    suspend fun dailyRecommendations(): List<NetEaseTrack> {
        val root = eapi(RECOMMEND_SONGS_PATH, org.json.JSONObject()) ?: return emptyList()
        return root.optJSONObject("data")
            ?.optJSONArray("dailySongs")
            .objects()
            .mapNotNull { it.track() }
    }

    /**
     * Playlist header (name, cover, track count).
     */
    suspend fun playlistHeader(playlistId: String): NetEasePlaylist? {
        val root = eapi(
            PLAYLIST_DETAIL_PATH,
            org.json.JSONObject()
                .put("id", playlistId)
                .put("n", "1")
                .put("s", "0"),
        ) ?: return null

        return root.optJSONObject("playlist")?.playlist()
    }

    /**
     * Track ids of a playlist.
     *
     * `/v6/playlist/detail` frequently returns only `trackIds`; the per-track metadata has to be
     * fetched separately.
     */
    suspend fun playlistTrackIds(playlistId: String): List<Long>? {
        val root = eapi(
            PLAYLIST_DETAIL_PATH,
            org.json.JSONObject()
                .put("id", playlistId)
                .put("n", "1000")
                .put("s", "0"),
        ) ?: return null

        return root.optJSONObject("playlist")
            ?.optJSONArray("trackIds")
            .objects()
            .mapNotNull { it.optLong("id", 0L).takeIf { id -> id > 0L } }
    }

    /**
     * Fetch metadata for [ids] through the batch song detail endpoint.
     */
    suspend fun songsDetail(ids: List<Long>): List<NetEaseTrack> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        // The endpoint caps the batch size; chunking keeps large playlists from being rejected.
        return ids.chunked(SONG_DETAIL_BATCH).flatMap { chunk ->
            val payload = org.json.JSONObject().put(
                "c",
                org.json.JSONArray().apply {
                    chunk.forEach { id -> put(org.json.JSONObject().put("id", id.toString())) }
                }.toString(),
            )

            val root = eapi(SONG_DETAIL_PATH, payload) ?: return@flatMap emptyList()
            root.optJSONArray("songs").objects().mapNotNull { it.track() }
        }
    }

    /**
     * Resolve a playable URL for [songId].
     *
     * NetEase does **not** report entitlement problems as errors: a non-VIP account silently
     * receives a ~45 second clip (`freeTrialInfo` present), and unavailable tracks return no URL
     * at all. Both are classified here so the caller can tell "full track" from "trial" from
     * "cannot play", instead of discovering it when the audio stops halfway.
     *
     * @return [SongUrl] with a null [SongUrl.url] when nothing is playable.
     */
    suspend fun songUrl(songId: Long, level: String = "exhigh"): SongUrl {
        // Walk down the quality ladder: the requested level may be unavailable for this account.
        for (tryLevel in ladderFrom(level)) {
            val root = eapi(
                SONG_URL_PATH,
                org.json.JSONObject().apply {
                    put("ids", org.json.JSONArray().put(songId).toString())
                    put("level", tryLevel)
                    put("encodeType", "flac")
                    put("header", defaultHeader())
                },
                useInterface = true,
            ) ?: continue

            val item = root.optJSONArray("data")?.optJSONObject(0) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() && it != "null" }

            Log.d(
                LOG_TAG,
                "songUrl id=$songId level=$tryLevel code=${item.optInt("code", -1)} " +
                    "fee=${item.optInt("fee", -1)} " +
                    "trial=${item.optJSONObject("freeTrialInfo") != null} " +
                    "hasUrl=${url != null}",
            )

            if (url == null) {
                continue
            }

            val trial = item.optJSONObject("freeTrialInfo")
            return SongUrl(
                url = url,
                actualLevel = item.optString("level").takeIf { it.isNotBlank() },
                requestedLevel = tryLevel,
                isTrial = trial != null,
                trialEndMs = trial?.optInt("end", 0)?.times(1000L)?.takeIf { it > 0L },
            )
        }

        return SongUrl(url = null, actualLevel = null, requestedLevel = level, isTrial = false)
    }

    /**
     * Resolve URLs for many songs in a single request.
     *
     * The endpoint accepts an id array, so a playlist costs one round trip instead of one per
     * track. Entitlement is classified per entry exactly as in [songUrl].
     */
    suspend fun songUrls(ids: List<Long>, level: String = "exhigh"): Map<Long, SongUrl> {
        if (ids.isEmpty()) {
            return emptyMap()
        }

        val result = mutableMapOf<Long, SongUrl>()

        for (tryLevel in ladderFrom(level)) {
            val pending = ids.filterNot { result.containsKey(it) }
            if (pending.isEmpty()) {
                break
            }

            val root = eapi(
                SONG_URL_PATH,
                org.json.JSONObject().apply {
                    put(
                        "ids",
                        org.json.JSONArray().apply { pending.forEach { put(it) } }.toString(),
                    )
                    put("level", tryLevel)
                    put("encodeType", "flac")
                    put("header", defaultHeader())
                },
                useInterface = true,
            ) ?: break

            val data = root.optJSONArray("data")?.objects().orEmpty()

            for (item in data) {
                val id = item.optLong("id", 0L)
                if (id <= 0L || result.containsKey(id)) {
                    continue
                }

                val url = item.optString("url").takeIf { it.isNotBlank() && it != "null" }
                    ?: continue

                val trial = item.optJSONObject("freeTrialInfo")
                result[id] = SongUrl(
                    url = url,
                    actualLevel = item.optString("level").takeIf { it.isNotBlank() },
                    requestedLevel = tryLevel,
                    isTrial = trial != null,
                    trialEndMs = trial?.optInt("end", 0)?.times(1000L)?.takeIf { it > 0L },
                )
            }
        }

        return result
    }

    /** Quality ladder, highest first. */
    private fun ladderFrom(level: String): List<String> = when (level) {
        "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
        "hires" -> listOf("hires", "lossless", "exhigh", "higher", "standard")
        "exhigh" -> listOf("exhigh", "higher", "standard")
        "higher" -> listOf("higher", "standard")
        else -> listOf("standard")
    }

    companion object {
        const val WEB_HOST = "https://music.163.com"
        const val INTERFACE_HOST = "https://interface.music.163.com"

        private const val APP_VERSION = "3.0.6"
        private const val OS_VERSION = "10.0.19045"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val REFERER = "https://music.163.com/"

        /** Account lookup. An expired session answers with `account: null`, not an error. */
        private const val ACCOUNT_PATH = "/api/w/nuser/account/get"

        private const val USER_PLAYLIST_PATH = "/api/user/playlist"
        private const val ALBUM_SUBLIST_PATH = "/api/album/sublist"
        private const val RECOMMEND_SONGS_PATH = "/api/v3/discovery/recommend/songs"
        private const val PLAYLIST_DETAIL_PATH = "/api/v6/playlist/detail"
        private const val SONG_DETAIL_PATH = "/api/v3/song/detail"
        private const val SONG_URL_PATH = "/api/song/enhance/player/url/v1"

        private const val LOG_TAG = "NetEaseClient"

        /** The batch song detail endpoint rejects very large batches. */
        private const val SONG_DETAIL_BATCH = 500

        /**
         * Stable per-install device identifier.
         *
         * Derived from [Build.FINGERPRINT] so it stays the same across app restarts without
         * needing storage, and differs between devices.
         */
        val deviceId: String by lazy {
            runCatching {
                UUID.nameUUIDFromBytes(Build.FINGERPRINT.toByteArray()).toString()
                    .replace("-", "")
                    .take(16)
            }.getOrDefault("pawam0000000000")
        }

        /**
         * Create the OkHttp cache NetEase calls share.
         */
        fun defaultCache(context: Context) = Cache(context.cacheDir, 20L * 1024 * 1024)
    }
}

/**
 * Outcome of a NetEase URL resolution.
 *
 * @param url Null when nothing was playable (no copyright, VIP-only, or the account lost access).
 * @param actualLevel The level the server actually served, which may be lower than requested.
 * @param isTrial True when only a preview clip was returned.
 * @param trialEndMs End of the preview clip, when it is a trial.
 */
data class SongUrl(
    val url: String?,
    val actualLevel: String?,
    val requestedLevel: String,
    val isTrial: Boolean,
    val trialEndMs: Long? = null,
)