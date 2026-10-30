/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Outcome of an Apple Music API request.
 *
 * The distinction matters to the UI: [Unauthorized] means "you need to sign in or supply a
 * developer token", while [Failed] is a transient/technical problem. Collapsing both into a null
 * would make an expired session look like a broken app.
 */
sealed interface AppleMusicResult<out T> {
    data class Success<T>(val data: T) : AppleMusicResult<T>

    /** No developer token, or Apple rejected it / the user token. */
    data object Unauthorized : AppleMusicResult<Nothing>

    /** The resource genuinely does not exist. */
    data object NotFound : AppleMusicResult<Nothing>

    data class Failed(val throwable: Throwable? = null) : AppleMusicResult<Nothing>
}

/**
 * Read client for `api.music.apple.com`.
 *
 * Header rules (these are contractual, not stylistic):
 * - Catalog endpoints (search, album/artist/song metadata) need `Authorization: Bearer <dev>`.
 * - User endpoints (`/v1/me/...`) additionally need `Music-User-Token`.
 * - `storefront` is part of the URL path, never a header.
 *
 * Nothing here downloads audio: playback leases are a separate, DRM-gated concern handled by
 * [AppleMusicPlayback].
 */
/**
 * How long library metadata may be served from disk before it is fetched again.
 *
 * Kept short on purpose: a longer window was tried and made the library show stale and empty
 * listings (an empty or partial answer stayed cached for hours), which looks like the library
 * having lost its contents. Ten minutes still makes repeated navigation instant.
 */
private const val CACHE_MAX_AGE_SECONDS = 10 * 60

class AppleMusicClient(
    private val tokenProvider: AppleMusicTokenProvider,
    private val credentials: () -> AppleMusicCredentials,
) {
    // ---------------------------------------------------------------------
    // Catalog
    // ---------------------------------------------------------------------

    suspend fun search(term: String, limit: Int = 25): AppleMusicResult<AppleMusicSearchResults> =
        authenticatedRequest { token ->
            val url = url("catalog", storefront(), "search") {
                addQueryParameter("term", term)
                addQueryParameter("types", "songs,albums,artists")
                addQueryParameter("limit", limit.toString())
            } ?: return@authenticatedRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken = null)
                ?: return@authenticatedRequest AppleMusicResult.Failed()
            val results = root.optJSONObject("results") ?: JSONObject()

            AppleMusicResult.Success(
                AppleMusicSearchResults(
                    audios = results.resourceList("songs").mapNotNull { it.toAudio(storefront()) },
                    albums = results.resourceList("albums").mapNotNull { it.toAlbum(storefront()) },
                    artists = results.resourceList("artists").mapNotNull { it.toArtist(storefront()) },
                )
            )
        }

    /**
     * Fetch a single catalog song.
     *
     * Needed because a song URI only carries the id: the album relationship is not always known
     * when the caller asks for the details of one track.
     */
    suspend fun song(songId: String): AppleMusicResult<AppleMusicAudio> =
        authenticatedRequest { token ->
            val url = url("catalog", storefront(), "songs", songId)
                ?: return@authenticatedRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken = null)
                ?: return@authenticatedRequest AppleMusicResult.Failed()
            val resource = root.dataArray().firstOrNull()
                ?: return@authenticatedRequest AppleMusicResult.NotFound

            val audio = resource.toAudio(storefront())
                ?: return@authenticatedRequest AppleMusicResult.NotFound

            AppleMusicResult.Success(audio)
        }

    suspend fun album(albumId: String): AppleMusicResult<Pair<AppleMusicAlbum, List<AppleMusicAudio>>> =
        authenticatedRequest { token ->
            val url = url("catalog", storefront(), "albums", albumId) {
                addQueryParameter("include", "tracks")
            } ?: return@authenticatedRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken = null)
                ?: return@authenticatedRequest AppleMusicResult.Failed()
            val resource = root.dataArray().firstOrNull()
                ?: return@authenticatedRequest AppleMusicResult.NotFound

            val album = resource.toAlbum(storefront())
                ?: return@authenticatedRequest AppleMusicResult.NotFound
            val tracks = resource.optJSONObject("relationships")
                ?.optJSONObject("tracks")
                ?.optJSONArray("data")
                .toObjectList()
                .mapNotNull { it.toAudio(storefront()) }

            AppleMusicResult.Success(album to tracks)
        }

    suspend fun artist(
        artistId: String,
    ): AppleMusicResult<Pair<AppleMusicArtist, List<AppleMusicAlbum>>> = authenticatedRequest { token ->
        val url = url("catalog", storefront(), "artists", artistId) {
            addQueryParameter("include", "albums")
        } ?: return@authenticatedRequest AppleMusicResult.Failed()

        val root = getJson(url, token, userToken = null)
            ?: return@authenticatedRequest AppleMusicResult.Failed()
        val resource = root.dataArray().firstOrNull()
            ?: return@authenticatedRequest AppleMusicResult.NotFound

        val artist = resource.toArtist(storefront())
            ?: return@authenticatedRequest AppleMusicResult.NotFound
        val albums = resource.optJSONObject("relationships")
            ?.optJSONObject("albums")
            ?.optJSONArray("data")
            .toObjectList()
            .mapNotNull { it.toAlbum(storefront()) }

        AppleMusicResult.Success(artist to albums)
    }

    // ---------------------------------------------------------------------
    // User library
    // ---------------------------------------------------------------------

    /**
     * Resolve the catalog id for a library song.
     *
     * Library items are addressed by ids like `i.XyZ`, which the playback endpoints do not accept.
     * Asking Apple for the item with `include=catalog` yields the catalog counterpart, whose id is
     * what playback, licences and metadata lookups actually need.
     */
    suspend fun catalogIdForLibrarySong(libraryId: String): AppleMusicResult<String> =
        userRequest { token, userToken ->
            val url = url("me", "library", "songs", libraryId) {
                addQueryParameter("include", "catalog")
            } ?: return@userRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken)
                ?: return@userRequest AppleMusicResult.Failed()
            val resource = root.dataArray().firstOrNull()
                ?: return@userRequest AppleMusicResult.NotFound

            val catalogId = resource.optJSONObject("relationships")
                ?.optJSONObject("catalog")
                ?.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("id")
                ?.takeIf { it.isNotBlank() }

            catalogId?.let { AppleMusicResult.Success(it) }
                ?: AppleMusicResult.NotFound
        }

    suspend fun libraryPlaylists(limit: Int = 100): AppleMusicResult<List<AppleMusicPlaylist>> =
        userRequest { token, userToken ->
            val url = url("me", "library", "playlists") {
                addQueryParameter("limit", limit.toString())
            } ?: return@userRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken) ?: return@userRequest AppleMusicResult.Failed()
            AppleMusicResult.Success(root.dataArray().mapNotNull { it.toPlaylist(storefront()) })
        }

    /**
     * Fetch one playlist together with its tracks.
     *
     * Library playlists (`p.*` ids) are read through `/me/library/playlists`, catalog playlists
     * through `/catalog/{storefront}/playlists`; both expose their tracks under the `tracks`
     * relationship, so the parsing is shared.
     */
    suspend fun playlist(
        playlistId: String,
        storefront: String,
    ): AppleMusicResult<Pair<AppleMusicPlaylist, List<AppleMusicAudio>>> {
        val isLibrary = playlistId.startsWith("p.")

        return if (isLibrary) {
            userRequest { token, userToken ->
                val url = url("me", "library", "playlists", playlistId) {
                    addQueryParameter("include", "tracks")
                } ?: return@userRequest AppleMusicResult.Failed()

                readPlaylistWithTracks(url, token, userToken, storefront)
            }
        } else {
            authenticatedRequest { token ->
                val url = url("catalog", storefront, "playlists", playlistId) {
                    addQueryParameter("include", "tracks")
                } ?: return@authenticatedRequest AppleMusicResult.Failed()

                readPlaylistWithTracks(url, token, userToken = null, storefront)
            }
        }
    }

    private suspend fun readPlaylistWithTracks(
        url: String,
        token: String,
        userToken: String?,
        storefront: String,
    ): AppleMusicResult<Pair<AppleMusicPlaylist, List<AppleMusicAudio>>> {
        val root = getJson(url, token, userToken)
            ?: return AppleMusicResult.Failed()
        val resource = root.dataArray().firstOrNull()
            ?: return AppleMusicResult.NotFound

        val playlist = resource.toPlaylist(storefront)
            ?: return AppleMusicResult.NotFound
        val tracks = resource.optJSONObject("relationships")
            ?.optJSONObject("tracks")
            ?.optJSONArray("data")
            .toObjectList()
            .mapNotNull { it.toAudio(storefront) }

        return AppleMusicResult.Success(playlist to tracks)
    }

    suspend fun libraryAlbums(limit: Int = 100): AppleMusicResult<List<AppleMusicAlbum>> =
        userRequest { token, userToken ->
            val url = url("me", "library", "albums") {
                addQueryParameter("limit", limit.toString())
                // Ensures we get real catalog ids rather than `l.*` library ids.
                addQueryParameter("include", "catalog")
            } ?: return@userRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken) ?: return@userRequest AppleMusicResult.Failed()
            AppleMusicResult.Success(root.dataArray().mapNotNull { it.toLibraryAlbum(storefront()) })
        }

    suspend fun librarySongs(limit: Int = 100): AppleMusicResult<List<AppleMusicAudio>> =
        userRequest { token, userToken ->
            val url = url("me", "library", "songs") {
                addQueryParameter("limit", limit.toString())
                addQueryParameter("include", "catalog")
            } ?: return@userRequest AppleMusicResult.Failed()

            val root = getJson(url, token, userToken) ?: return@userRequest AppleMusicResult.Failed()
            AppleMusicResult.Success(root.dataArray().mapNotNull { it.toLibraryAudio() })
        }

    /**
     * Account and subscription state.
     *
     * Read this before promising full-length playback: an active subscription is what the
     * playback lease actually checks.
     */
    suspend fun account(): AppleMusicResult<AppleMusicAccount> = userRequest { token, userToken ->
        val url = url("me", "account") { addQueryParameter("meta", "subscription") }
            ?: return@userRequest AppleMusicResult.Failed()

        val root = getJson(url, token, userToken) ?: return@userRequest AppleMusicResult.Failed()
        val subscription = root.optJSONObject("meta")?.optJSONObject("subscription")

        AppleMusicResult.Success(
            AppleMusicAccount(
                subscriptionActive = subscription?.optBoolean("active") ?: false,
                storefront = subscription?.optString("storefront")?.takeIf { it.isNotBlank() },
            )
        )
    }

    // ---------------------------------------------------------------------
    // Lyrics
    // ---------------------------------------------------------------------

    /**
     * Fetch lyrics as TTML for [songId].
     *
     * Apple exposes lyrics only through its private `amp-api` host, not the documented REST
     * endpoint, so this is best-effort: `syllable-lyrics` carries per-word timing and is preferred,
     * with the line-level `lyrics` endpoint as a fallback. Both may legitimately return nothing
     * (instrumentals), which is reported as [AppleMusicResult.NotFound] rather than an error.
     */
    suspend fun lyricsTtml(songId: String): AppleMusicResult<String> =
        userRequest { token, userToken ->
            val storefront = storefront()

            for (endpoint in LYRIC_ENDPOINTS) {
                val url = "$LYRICS_HOST/v1/catalog/$storefront/songs/$songId/$endpoint"

                val body = withContext(Dispatchers.IO) {
                    runCatching {
                        val request = Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer $token")
                            .header("Music-User-Token", userToken)
                            .header("Origin", AppleMusicHttp.ORIGIN)
                            .header("User-Agent", AppleMusicHttp.USER_AGENT)
                            .build()

                        AppleMusicHttp.client.newCall(request).execute().use { response ->
                            if (response.code == 200) response.body?.string() else null
                        }
                    }.getOrNull()
                } ?: continue

                val ttml = runCatching {
                    JSONObject(body)
                        .optJSONArray("data")
                        ?.optJSONObject(0)
                        ?.optJSONObject("attributes")
                        ?.optString("ttml")
                }.getOrNull()

                if (!ttml.isNullOrBlank()) {
                    return@userRequest AppleMusicResult.Success(ttml)
                }
            }

            AppleMusicResult.NotFound
        }

    // ---------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------

    private suspend fun <T> authenticatedRequest(
        block: suspend (token: String) -> AppleMusicResult<T>,
    ): AppleMusicResult<T> {
        val token = tokenProvider.developerToken()
            ?: return AppleMusicResult.Unauthorized

        return block(token)
    }

    private suspend fun <T> userRequest(
        block: suspend (token: String, userToken: String) -> AppleMusicResult<T>,
    ): AppleMusicResult<T> {
        val token = tokenProvider.developerToken()
            ?: return AppleMusicResult.Unauthorized
        val userToken = credentials().musicUserToken.takeIf { it.isNotBlank() }
            ?: return AppleMusicResult.Unauthorized

        return block(token, userToken)
    }

    /**
     * POST a JSON body and return the parsed response.
     *
     * Mutations answer with `201 Created` (and sometimes `204 No Content`), so those are treated as
     * success alongside `200`.
     */
    private suspend fun postJson(
        url: String,
        developerToken: String,
        userToken: String?,
        body: JSONObject,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $developerToken")
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)

        if (userToken != null) {
            builder.header("Music-User-Token", userToken)
        }

        runCatching {
            AppleMusicHttp.client.newCall(builder.build()).execute().use { response ->
                Log.d(LOG_TAG, "POST ${url.substringAfter("/v1")} -> ${response.code}")

                when (response.code) {
                    200, 201 -> JSONObject(response.body?.string().orEmpty())
                    204 -> JSONObject()
                    401, 403 -> JSONObject().put(STATUS_MARKER, STATUS_UNAUTHORIZED)
                    404 -> JSONObject().put(STATUS_MARKER, STATUS_NOT_FOUND)
                    else -> {
                        Log.w(
                            LOG_TAG,
                            "Unexpected ${response.code}: " +
                                response.body?.string()?.take(200),
                        )
                        null
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Search the user's own library.
     *
     * Apple exposes this under `/me/library/search`, which only searches library content — the
     * catalog search endpoint would return unrelated store results for the same query.
     */
    suspend fun librarySearch(
        term: String,
        limit: Int = 25,
    ): AppleMusicResult<AppleMusicSearchResults> = userRequest { token, userToken ->
        val url = url("me", "library", "search") {
            addQueryParameter("term", term)
            addQueryParameter("types", "library-albums,library-songs,library-artists")
            addQueryParameter("limit", limit.toString())
        } ?: return@userRequest AppleMusicResult.Failed()

        val root = getJson(url, token, userToken)
            ?: return@userRequest AppleMusicResult.Failed()
        val results = root.optJSONObject("results") ?: JSONObject()

        AppleMusicResult.Success(
            AppleMusicSearchResults(
                audios = results.resourceList("library-songs")
                    .mapNotNull { it.toAudio(storefront()) },
                albums = results.resourceList("library-albums")
                    .mapNotNull { it.toLibraryAlbum(storefront()) },
                artists = results.resourceList("library-artists")
                    .mapNotNull { it.toArtist(storefront()) },
            )
        )
    }

    /**
     * Create a library playlist and return its catalog/library id.
     */
    suspend fun createPlaylist(name: String): AppleMusicResult<String> =
        userRequest { token, userToken ->
            val url = url("me", "library", "playlists")
                ?: return@userRequest AppleMusicResult.Failed()

            val payload = JSONObject().apply {
                put(
                    "attributes",
                    JSONObject().put("name", name),
                )
            }

            val root = postJson(url, token, userToken, payload)
                ?: return@userRequest AppleMusicResult.Failed()

            root.dataArray().firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }
                ?.let { AppleMusicResult.Success(it) }
                ?: AppleMusicResult.Failed()
        }

    private suspend fun getJson(
        url: String,
        developerToken: String,
        userToken: String?,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $developerToken")
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)
            // Apple's API sends no cache validators, so OkHttp never stores these responses and
            // every library visit re-fetches the whole listing. Asking for a long max-age is what
            // makes the shared disk cache effective.
            .cacheControl(
                okhttp3.CacheControl.Builder()
                    .maxAge(CACHE_MAX_AGE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            )

        if (userToken != null) {
            builder.header("Music-User-Token", userToken)
        }

        runCatching {
            AppleMusicHttp.client.newCall(builder.build()).execute().use { response ->
                Log.d(LOG_TAG, "GET ${url.substringAfter("/v1")} -> ${response.code}")

                when (response.code) {
                    200 -> JSONObject(response.body?.string().orEmpty())
                    401, 403 -> JSONObject().put(STATUS_MARKER, STATUS_UNAUTHORIZED)
                    404 -> JSONObject().put(STATUS_MARKER, STATUS_NOT_FOUND)
                    else -> {
                        // The body distinguishes "wrong endpoint" from "wrong token" quickly.
                        Log.w(
                            LOG_TAG,
                            "Unexpected ${response.code}: " +
                                response.body?.string()?.take(200),
                        )
                        null
                    }
                }
            }
        }.getOrNull()?.let { json ->
            when (json.optString(STATUS_MARKER)) {
                STATUS_UNAUTHORIZED -> null
                STATUS_NOT_FOUND -> null
                else -> if (json.has(STATUS_MARKER)) null else json
            }
        }
    }

    private fun url(
        vararg segments: String,
        query: okhttp3.HttpUrl.Builder.() -> Unit = {},
    ): String? {
        val base = (AppleMusicHttp.API_HOST + "/v1").toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        // Apply the caller's query parameters. Without this the search/detail requests would be
        // sent with no `term`/`include`, and the API would return unfiltered or incomplete data.
        builder.apply(query)
        return builder.build().toString()
    }

    private fun storefront() = credentials().storefront.ifBlank { DEFAULT_STOREFRONT }

    companion object {
        private const val LOG_TAG = "AppleMusicClient"

        /** Private lyrics host; not part of the documented public API. */
        private const val LYRICS_HOST = "https://amp-api.music.apple.com"

        /** Preferred first: `syllable-lyrics` carries per-word timing. */
        private val LYRIC_ENDPOINTS = listOf("syllable-lyrics", "lyrics")

        const val DEFAULT_STOREFRONT = "us"
        private const val STATUS_MARKER = "__pawam_status"
        private const val STATUS_UNAUTHORIZED = "unauthorized"
        private const val STATUS_NOT_FOUND = "not_found"
    }
}

// -------------------------------------------------------------------------
// JSON helpers
// -------------------------------------------------------------------------

internal fun JSONObject?.dataArray(): List<JSONObject> =
    this?.optJSONArray("data").toObjectList()

internal fun JSONArray?.toObjectList(): List<JSONObject> = buildList {
    val array = this@toObjectList ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { add(it) }
    }
}

/** `results.<type>.data` in a search response. */
internal fun JSONObject.resourceList(type: String): List<JSONObject> =
    optJSONObject(type)?.dataArray().orEmpty()
