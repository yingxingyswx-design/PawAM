/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.netease

import org.json.JSONArray
import org.json.JSONObject

/**
 * A NetEase playlist as returned by `/eapi/user/playlist`.
 *
 * @param id Playlist id.
 * @param name Display name.
 * @param artworkUrl Cover image, when present.
 * @param trackCount Number of tracks the server reports.
 * @param isFavorites True for the special "liked music" playlist (`specialType == 5`), which is a
 *   playlist in the data model but not one the user created.
 */
data class NetEasePlaylist(
    val id: Long,
    val name: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val isFavorites: Boolean,
)

/**
 * An album subscribed in the user's library.
 */
data class NetEaseAlbum(
    val id: Long,
    val name: String,
    val artistName: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val year: Int?,
)

/**
 * A track, from search, a playlist or a recommendation shelf.
 *
 * @param fee NetEase's entitlement marker: `1` VIP-only, `4` album purchase, `8` free/low
 *   quality. Kept because it explains why a track may not be fully playable.
 * @param isTrial True when the server marks this item as trial-only for the current session.
 */
data class NetEaseTrack(
    val id: Long,
    val name: String,
    val artistNames: String,
    val albumId: Long?,
    val albumName: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val fee: Int,
    val isTrial: Boolean,
)

internal fun JSONArray?.objects(): List<JSONObject> = buildList {
    val array = this@objects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { add(it) }
    }
}

internal fun JSONObject.playlist(): NetEasePlaylist? {
    val id = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val name = optString("name").takeIf { it.isNotBlank() } ?: return null

    return NetEasePlaylist(
        id = id,
        name = name,
        artworkUrl = optString("coverImgUrl").takeIf { it.isNotBlank() },
        trackCount = optInt("trackCount", 0),
        // `specialType == 5` is the "liked music" pseudo-playlist.
        isFavorites = optInt("specialType", 0) == 5,
    )
}

internal fun JSONObject.album(): NetEaseAlbum? {
    val id = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val name = optString("name").takeIf { it.isNotBlank() } ?: return null

    val artistName = optJSONArray("artists").objects()
        .mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }
        .joinToString(", ")
        .ifBlank { optJSONObject("artist")?.optString("name").orEmpty() }

    return NetEaseAlbum(
        id = id,
        name = name,
        artistName = artistName,
        artworkUrl = optString("picUrl").takeIf { it.isNotBlank() },
        trackCount = optInt("size", 0),
        year = optLong("publishTime", 0L)
            .takeIf { it > 0L }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year },
    )
}

internal fun JSONObject.track(): NetEaseTrack? {
    val id = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val name = optString("name").takeIf { it.isNotBlank() } ?: return null

    // `ar` (artists) and `al` (album) are the modern shape; `artists`/`album` appear in older
    // payloads such as daily recommendations.
    val artists = optJSONArray("ar").objects()
        .ifEmpty { optJSONArray("artists").objects() }
        .mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }
        .joinToString(", ")

    val albumObject = optJSONObject("al") ?: optJSONObject("album")

    return NetEaseTrack(
        id = id,
        name = name,
        artistNames = artists,
        albumId = albumObject?.optLong("id", 0L)?.takeIf { it > 0L },
        albumName = albumObject?.optString("name")?.takeIf { it.isNotBlank() },
        artworkUrl = albumObject?.optString("picUrl")?.takeIf { it.isNotBlank() },
        durationMs = optLong("dt", optLong("duration", 0L)).takeIf { it > 0L },
        fee = optInt("fee", 0),
        // Presence of `freeTrialInfo` marks the item as trial-only for this session.
        isTrial = optJSONObject("freeTrialInfo") != null,
    )
}
