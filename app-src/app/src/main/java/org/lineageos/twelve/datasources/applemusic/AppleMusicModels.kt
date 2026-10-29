/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import android.net.Uri
import org.json.JSONObject

/**
 * Apple Music identifiers, **explicitly separated by concern**.
 *
 * Mixing these up is the classic Apple Music client bug:
 *
 * - a *library* id (`i.xxxx` / `l.xxxx`) is scoped to one user's library and cannot be used to
 *   fetch catalog metadata or a playback lease;
 * - a *catalog* id (`1234567890`, the Adam ID) is what catalog lookups and `webPlayback` need.
 *
 * A library resource therefore has to be resolved through the `include=catalog` relationship
 * before it can be played. [libraryId] and [catalogId] are kept apart so that mistake cannot be
 * made silently.
 */
data class AppleMusicIds(
    val catalogId: String?,
    val libraryId: String?,
) {
    /** Whether this item can be used for catalog lookups and playback. */
    val isPlayable: Boolean get() = !catalogId.isNullOrBlank()

    companion object {
        val EMPTY = AppleMusicIds(catalogId = null, libraryId = null)
    }
}

/**
 * Everything needed to talk to Apple Music on behalf of one account.
 *
 * The three values are **independent credentials** and must not be conflated:
 *
 * - [developerToken] proves the *application* may call the API (a JWT).
 * - [musicUserToken] proves *which user* is asking; it is the `media-user-token` cookie value and
 *   is required for the user-library endpoints and for playback.
 * - [storefront] selects the catalog region and travels in the URL path, not a header.
 *
 * A developer token never grants access to a user's library on its own, and a user token is
 * useless without a developer token.
 */
data class AppleMusicCredentials(
    val developerToken: String = "",
    val musicUserToken: String = "",
    val storefront: String = AppleMusicClient.DEFAULT_STOREFRONT,
) {
    val isSignedIn: Boolean get() = musicUserToken.isNotBlank()
}

/** Account/subscription snapshot from `/v1/me/account`. */
data class AppleMusicAccount(
    val subscriptionActive: Boolean,
    val storefront: String?,
)

/** Aggregated search hits. */
data class AppleMusicSearchResults(
    val audios: List<AppleMusicAudio> = emptyList(),
    val albums: List<AppleMusicAlbum> = emptyList(),
    val artists: List<AppleMusicArtist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = audios.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

data class AppleMusicAlbum(
    val ids: AppleMusicIds,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val releaseDate: String?,
    val year: Int?,
)

data class AppleMusicArtist(
    val ids: AppleMusicIds,
    val name: String,
    val artworkUrl: String?,
)

data class AppleMusicAudio(
    val ids: AppleMusicIds,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val albumId: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val isExplicit: Boolean,
    /** Unencrypted ~30s preview. Always playable; this is *not* full-length playback. */
    val previewUrl: String?,
)

data class AppleMusicPlaylist(
    val ids: AppleMusicIds,
    val name: String,
    val curator: String?,
    val artworkUrl: String?,
    val trackCount: Int,
)

// -------------------------------------------------------------------------
// Apple Music resource URIs
// -------------------------------------------------------------------------

/**
 * URI scheme for Apple Music items.
 *
 * Twelve identifies every media item by a [Uri]; using a dedicated scheme keeps Apple Music items
 * from colliding with MediaStore or other providers, and lets [AppleMusicDataSource] recognise its
 * own items without guessing.
 *
 * Layout: `applemusic://catalog/<providerId>/<kind>/<storefront>/<id>`
 *
 * The provider id is part of the path because a user may configure more than one Apple Music
 * account (different storefronts). Without it, resolving "which provider owns this item" would
 * have to guess.
 */
object AppleMusicUri {
    const val SCHEME = "applemusic"
    const val AUTHORITY = "catalog"

    const val KIND_ALBUM = "album"
    const val KIND_ARTIST = "artist"
    const val KIND_AUDIO = "audio"
    const val KIND_PLAYLIST = "playlist"

    data class Parsed(
        val providerId: Long,
        val kind: String,
        val storefront: String,
        val id: String,
    )

    fun of(
        providerId: Long,
        kind: String,
        storefront: String,
        id: String,
    ): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(providerId.toString())
        .appendPath(kind)
        .appendPath(storefront.ifBlank { AppleMusicClient.DEFAULT_STOREFRONT })
        .appendPath(id)
        .build()

    fun parse(uri: Uri): Parsed? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) {
            return null
        }

        val segments = uri.pathSegments
        if (segments.size != 4) {
            return null
        }

        return Parsed(
            providerId = segments[0].toLongOrNull() ?: return null,
            kind = segments[1],
            storefront = segments[2],
            id = segments[3],
        )
    }
}

// -------------------------------------------------------------------------
// JSON mapping
// -------------------------------------------------------------------------

/**
 * Turn an `attributes` object into display fields shared by albums/artists/songs.
 */
internal fun JSONObject.artworkUrl(size: Int = AppleMusicHttp.ARTWORK_SIZE): String? =
    optJSONObject("artwork")
        ?.optString("url")
        ?.takeIf { it.isNotBlank() }
        ?.replace("{w}", size.toString())
        ?.replace("{h}", size.toString())

internal fun JSONObject.firstGenreName(): String? =
    optJSONArray("genreNames")?.optString(0)?.takeIf { it.isNotBlank() }

/**
 * Resolve the pair of ids for a resource.
 *
 * Catalog responses carry the catalog id in `id`. Library responses return a `l.*`/`i.*` id and,
 * when requested with `include=catalog`, the real catalog resource under
 * `relationships.catalog.data[0]`. Using the library id as a catalog id produces 404s.
 */
internal fun JSONObject.ids(): AppleMusicIds {
    val catalogResource = optJSONObject("relationships")
        ?.optJSONObject("catalog")
        ?.optJSONArray("data")
        .toObjectList()
        .firstOrNull()

    val playParams = optJSONObject("attributes")?.optJSONObject("playParams")
    val rawId = optString("id").takeIf { it.isNotBlank() }

    return AppleMusicIds(
        catalogId = catalogResource?.optString("id")?.takeIf { it.isNotBlank() }
            ?: playParams?.optString("catalogId")?.takeIf { it.isNotBlank() && it != "null" }
            ?: rawId?.takeIf { !it.startsWith("l.") && !it.startsWith("i.") },
        libraryId = rawId?.takeIf { it.startsWith("l.") || it.startsWith("i.") },
    )
}

internal fun JSONObject.attributeSource(): JSONObject? =
    optJSONObject("relationships")
        ?.optJSONObject("catalog")
        ?.optJSONArray("data")
        .toObjectList()
        .firstOrNull()
        ?.optJSONObject("attributes")
        ?: optJSONObject("attributes")

internal fun JSONObject.toAlbum(storefront: String): AppleMusicAlbum? {
    val attributes = attributeSource() ?: return null
    val ids = ids()
    val title = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null

    val releaseDate = attributes.optString("releaseDate").takeIf { it.isNotBlank() }

    return AppleMusicAlbum(
        ids = ids,
        title = title,
        artistName = attributes.optString("artistName"),
        artworkUrl = attributes.artworkUrl(),
        trackCount = attributes.optInt("trackCount", 0),
        releaseDate = releaseDate,
        year = releaseDate?.take(4)?.toIntOrNull(),
    )
}

/** Library albums must resolve to a catalog id; drop entries that cannot. */
internal fun JSONObject.toLibraryAlbum(storefront: String): AppleMusicAlbum? =
    toAlbum(storefront)?.takeIf { it.ids.isPlayable }

internal fun JSONObject.toArtist(storefront: String): AppleMusicArtist? {
    val attributes = attributeSource() ?: return null
    val name = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null

    return AppleMusicArtist(
        ids = ids(),
        name = name,
        artworkUrl = attributes.artworkUrl(size = 400),
    )
}

internal fun JSONObject.toAudio(storefront: String): AppleMusicAudio? {
    val attributes = attributeSource() ?: return null
    val title = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null

    val preview = attributes.optJSONArray("previews")
        .toObjectList()
        .firstNotNullOfOrNull { it.optString("url").takeIf { url -> url.isNotBlank() } }

    return AppleMusicAudio(
        ids = ids(),
        title = title,
        artistName = attributes.optString("artistName"),
        albumTitle = attributes.optString("albumName").takeIf { it.isNotBlank() },
        albumId = attributes.optJSONObject("playParams")
            ?.optString("catalogId")
            ?.takeIf { it.isNotBlank() && it != "null" },
        artworkUrl = attributes.artworkUrl(),
        durationMs = attributes.optLong(
            "durationInMillis",
            attributes.optLong("duration", 0L),
        ).takeIf { it > 0L },
        trackNumber = attributes.optInt("trackNumber", 0).takeIf { it > 0 },
        discNumber = attributes.optInt("discNumber", 0).takeIf { it > 0 },
        isExplicit = attributes.optString("contentRating") == "explicit",
        previewUrl = preview,
    )
}

internal fun JSONObject.toLibraryAudio(): AppleMusicAudio? {
    val audio = toAudio(storefront = "") ?: return null
    return audio.takeIf { it.ids.isPlayable }
}

internal fun JSONObject.toPlaylist(storefront: String): AppleMusicPlaylist? {
    val attributes = attributeSource() ?: return null
    val name = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null

    return AppleMusicPlaylist(
        ids = ids(),
        name = name,
        curator = attributes.optString("curatorName").takeIf { it.isNotBlank() },
        artworkUrl = attributes.artworkUrl(),
        trackCount = attributes.optInt("trackCount", 0),
    )
}
