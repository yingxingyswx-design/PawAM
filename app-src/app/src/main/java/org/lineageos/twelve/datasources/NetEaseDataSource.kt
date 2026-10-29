/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.twelve.R
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.netease.NetEaseClient
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.ArtistWorks
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.DataSourceInformation
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.GenreContent
import org.lineageos.twelve.models.LocalizedString
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.Thumbnail
import org.lineageos.twelve.repositories.ProvidersRepository

/**
 * NetEase Cloud Music data source.
 *
 * ## Current scope
 *
 * Implemented: account status and catalog search. Everything else reports
 * [Error.NOT_IMPLEMENTED] instead of returning empty collections, so a half-finished area is
 * distinguishable from genuinely empty content.
 *
 * ## Playback is not implemented
 *
 * NetEase returns plain, unencrypted URLs (unlike Apple), but those URLs are entitlement-gated:
 * a non-VIP account silently receives a 45-second trial clip, and unavailable tracks return no URL
 * at all. Presenting such a URL without classifying it would reproduce the classic "song stops
 * halfway" bug, so playback is left out until the entitlement rules are implemented.
 */
class NetEaseDataSource(
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository,
    private val database: TwelveDatabase,
) : MediaDataSource {
    private class NetEaseInstance(
        val providerId: Long,
        val cookie: String,
        val nickname: String?,
    ) {
        val client = NetEaseClient(cookie)
    }

    private val instances: StateFlow<Map<Long, NetEaseInstance>> =
        providersRepository.allProvidersToArguments
            .mapLatest { allProviders ->
                allProviders
                    .filter { (provider, _) -> provider.type == ProviderType.NETEASE }
                    .associate { (provider, _) ->
                        val row = database.getNetEaseProviderDao()
                            .get(provider.identifier.typeId)
                            .firstOrNull()

                        provider.identifier.typeId to NetEaseInstance(
                            providerId = provider.identifier.typeId,
                            cookie = row?.cookie.orEmpty(),
                            nickname = row?.nickname,
                        )
                    }
            }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )

    private fun instanceFor(providerId: Long?) = providerId
        ?.let { instances.value[it] }
        ?: instances.value.values.firstOrNull()

    private fun <T> request(
        providerId: Long?,
        block: suspend (NetEaseInstance) -> Result<T, Error>,
    ): Flow<Result<T, Error>> = flow {
        val instance = instanceFor(providerId)
            ?: return@flow emit(Result.Failure(Error.AUTHENTICATION_REQUIRED))

        emit(block(instance))
    }

    // ---------------------------------------------------------------------

    override fun status(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<DataSourceInformation>>> =
        request(providerIdentifier.typeId) { instance ->
            if (instance.cookie.isBlank()) {
                return@request Result.Failure(Error.AUTHENTICATION_REQUIRED)
            }

            // A cookie that resolves to no account means the session has expired: NetEase answers
            // such requests with code 200 and `account: null` rather than an error.
            val account = instance.client.account()
                ?: return@request Result.Failure(Error.INVALID_CREDENTIALS)

            Result.Success(
                listOf(
                    DataSourceInformation(
                        key = "signed_in",
                        keyLocalizedString = localized(R.string.netease_signed_in),
                        value = LocalizedString.of(account.userId != null),
                    ),
                    DataSourceInformation(
                        key = "nickname",
                        keyLocalizedString = localized(R.string.netease_nickname),
                        value = LocalizedString.StringLocalizedString(
                            account.nickname ?: instance.nickname ?: "—"
                        ),
                    ),
                )
            )
        }

    override suspend fun mediaTypeOf(mediaItemUri: Uri): MediaType? {
        val parsed = NetEaseUri.parse(mediaItemUri) ?: return null

        return when (parsed.kind) {
            NetEaseUri.KIND_AUDIO -> MediaType.AUDIO
            NetEaseUri.KIND_ALBUM -> MediaType.ALBUM
            else -> null
        }
    }

    override fun providerOf(
        mediaItemUri: Uri,
    ): Flow<MediaRequestStatus<ProviderIdentifier>> {
        val parsed = NetEaseUri.parse(mediaItemUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return flowOf(
            Result.Success(ProviderIdentifier(ProviderType.NETEASE, parsed.providerId))
        )
    }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ): Flow<MediaRequestStatus<List<MediaItem<*>>>> =
        request(providerIdentifier.typeId) { instance ->
            val root = instance.client.eapi(
                "/api/cloudsearch/pc",
                JSONObject()
                    .put("s", query)
                    .put("type", 1)
                    .put("limit", 30),
            ) ?: return@request Result.Failure(Error.IO)

            val songs = root.optJSONObject("result")
                ?.optJSONArray("songs")
                .toObjectList()

            Result.Success(
                songs.mapNotNull { it.toAudioModel(instance.providerId) }
            )
        }

    // ---------------------------------------------------------------------
    // Not implemented yet
    // ---------------------------------------------------------------------

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<ActivityTab>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Album>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Artist>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Audio>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Genre>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Playlist>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun audio(audioUri: Uri): Flow<MediaRequestStatus<Audio>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun album(
        albumUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Album, List<Audio>>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun artist(
        artistUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Artist, ArtistWorks>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun genre(
        genreUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Genre, GenreContent>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun playlist(
        playlistUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Playlist, List<Audio>>>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override fun audioPlaylistsStatus(
        audioUri: Uri,
    ): Flow<MediaRequestStatus<List<Pair<Playlist, Boolean>>>> =
        flowOf(Result.Success(emptyList()))

    override fun lyrics(audioUri: Uri): Flow<Result<Lyrics, Error>> =
        flowOf(Result.Failure(Error.NOT_IMPLEMENTED))

    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ): MediaRequestStatus<Uri> = Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun renamePlaylist(playlistUri: Uri, name: String): MediaRequestStatus<Unit> =
        Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun deletePlaylist(playlistUri: Uri): MediaRequestStatus<Unit> =
        Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> = Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> = Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun onAudioPlayed(audioUri: Uri, positionMs: Long): MediaRequestStatus<Unit> =
        Result.Success(Unit)

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean,
    ): MediaRequestStatus<Unit> = Result.Failure(Error.NOT_IMPLEMENTED)
}

private fun localized(
    @androidx.annotation.StringRes stringResId: Int,
) = LocalizedString.StringResIdLocalizedString(stringResId)

private fun JSONArray?.toObjectList(): List<JSONObject> = buildList {
    val array = this@toObjectList ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { add(it) }
    }
}

/**
 * URI scheme for NetEase items: `netease://catalog/<providerId>/<kind>/<id>`.
 */
object NetEaseUri {
    const val SCHEME = "netease"
    const val AUTHORITY = "catalog"

    const val KIND_AUDIO = "audio"
    const val KIND_ALBUM = "album"

    data class Parsed(
        val providerId: Long,
        val kind: String,
        val id: String,
    )

    fun of(providerId: Long, kind: String, id: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(providerId.toString())
        .appendPath(kind)
        .appendPath(id)
        .build()

    fun parse(uri: Uri): Parsed? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) {
            return null
        }

        val segments = uri.pathSegments
        if (segments.size != 3) {
            return null
        }

        return Parsed(
            providerId = segments[0].toLongOrNull() ?: return null,
            kind = segments[1],
            id = segments[2],
        )
    }
}

/**
 * Map a `cloudsearch` song object onto [Audio].
 *
 * Playback is deliberately left null: see the class documentation.
 */
private fun JSONObject.toAudioModel(providerId: Long): Audio? {
    val id = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val title = optString("name").takeIf { it.isNotBlank() } ?: return null

    val artists = optJSONArray("ar").toObjectList()
        .mapNotNull { it.optString("name").takeIf { name -> name.isNotBlank() } }
        .joinToString(", ")

    val album = optJSONObject("al")
    val albumId = album?.optLong("id", 0L)?.takeIf { it > 0L }
    val artwork = album?.optString("picUrl")?.takeIf { it.isNotBlank() }

    return Audio(
        uri = NetEaseUri.of(providerId, NetEaseUri.KIND_AUDIO, id.toString()),
        thumbnail = artwork?.let {
            Thumbnail.Builder()
                .setUri(it.toUri())
                .setType(Thumbnail.Type.FRONT_COVER)
                .build()
        },
        playbackUri = null,
        mimeType = null,
        title = title,
        type = Audio.Type.MUSIC,
        durationMs = optLong("dt", 0L).takeIf { it > 0L },
        artistUri = null,
        artistName = artists.takeIf { it.isNotBlank() },
        albumUri = albumId?.let {
            NetEaseUri.of(providerId, NetEaseUri.KIND_ALBUM, it.toString())
        },
        albumTitle = album?.optString("name")?.takeIf { it.isNotBlank() },
        discNumber = null,
        trackNumber = null,
        genreUri = null,
        genreName = null,
        year = null,
        isFavorite = false,
    )
}
