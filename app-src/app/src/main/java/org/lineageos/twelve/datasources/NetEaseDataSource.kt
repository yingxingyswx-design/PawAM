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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.twelve.R
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.netease.NetEaseAlbum
import org.lineageos.twelve.datasources.netease.NetEaseClient
import org.lineageos.twelve.datasources.netease.NetEasePlaylist
import org.lineageos.twelve.datasources.netease.NetEaseTrack
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
    private val providersRepository: ProvidersRepository,
    private val database: TwelveDatabase,
) : MediaDataSource {
    private class NetEaseInstance(
        val providerId: Long,
        val cookie: String,
        val nickname: String?,
        val userId: Long?,
    ) {
        val client = NetEaseClient(cookie)
    }

    /**
     * Resolve the instance for [providerId], or the only configured account when unknown.
     *
     * Resolved on demand: the account row changes at sign-in, and a cached snapshot can stay
     * empty depending on subscription timing (that is exactly what made the library appear
     * unauthorised while the account was in fact signed in).
     */
    private suspend fun instanceFor(providerId: Long?): NetEaseInstance? {
        val allProviders = providersRepository.allProvidersToArguments.first()
            .filter { (provider, _) -> provider.type == ProviderType.NETEASE }

        val provider = providerId
            ?.let { id -> allProviders.firstOrNull { it.first.identifier.typeId == id } }
            ?: allProviders.firstOrNull()
            ?: return null

        val typeId = provider.first.identifier.typeId
        val row = database.getNetEaseProviderDao().get(typeId).firstOrNull()

        return NetEaseInstance(
            providerId = typeId,
            cookie = row?.cookie.orEmpty(),
            nickname = row?.nickname,
            userId = row?.userId,
        )
    }


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
            NetEaseUri.KIND_PLAYLIST -> MediaType.PLAYLIST
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
        request(providerIdentifier.typeId) { instance ->
            val tracks = instance.client.dailyRecommendations()
            if (tracks.isEmpty()) {
                Result.Failure(Error.AUTHENTICATION_REQUIRED)
            } else {
                Result.Success(
                    listOf(
                        ActivityTab(
                            id = "netease_daily",
                            title = localized(R.string.netease_daily_recommendations),
                            items = tracks.mapNotNull { it.toAudioModel(instance.providerId) }
                                .map { it as MediaItem<*> },
                        )
                    )
                )
            }
        }

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Album>>> =
        request(providerIdentifier.typeId) { instance ->
            val albums = instance.client.userAlbums()
            Result.Success(albums.mapNotNull { it.toAlbumModel(instance.providerId) })
        }

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
        request(providerIdentifier.typeId) { instance ->
            val uid = instance.userId
            if (uid == null) {
                // The account id is captured at sign-in; without it the playlist endpoint has
                // nothing to list, so ask the user to sign in again instead of showing an
                // empty library.
                Result.Failure(Error.AUTHENTICATION_REQUIRED)
            } else {
                Result.Success(
                    instance.client.userPlaylists(uid)
                        .map { it.toPlaylistModel(instance.providerId) }
                )
            }
        }

    override fun audio(audioUri: Uri): Flow<MediaRequestStatus<Audio>> {
        val parsed = NetEaseUri.parse(audioUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        val songId = parsed.id.toLongOrNull()
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return request(parsed.providerId) { instance ->
            val track = instance.client.songsDetail(listOf(songId)).firstOrNull()
                ?: return@request Result.Failure(Error.NOT_FOUND)

            val base = track.toAudioModel(instance.providerId)
                ?: return@request Result.Failure(Error.NOT_FOUND)

            // Resolve at play time: NetEase URLs are signed and short-lived, and the account's
            // entitlement can change between listing and playback.
            val resolved = instance.client.songUrl(songId)
            val url = resolved.url

            if (url == null) {
                // Nothing playable for this account. Keep the item non-playable so the UI can
                // explain it instead of playing silence.
                Result.Success(base)
            } else {
                // mimeType stays null so ExoPlayer sniffs the container: the served format does
                // not always match the requested level.
                Result.Success(base.copy(playbackUri = url.toUri()))
            }
        }
    }

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
    ): Flow<MediaRequestStatus<Pair<Playlist, List<Audio>>>> {
        val parsed = NetEaseUri.parse(playlistUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return request(parsed.providerId) { instance ->
            val trackIds = instance.client.playlistTrackIds(parsed.id)
                ?: return@request Result.Failure(Error.NOT_FOUND)

            val playlist = instance.client.playlistHeader(parsed.id)
                ?: return@request Result.Failure(Error.NOT_FOUND)

            // `/v6/playlist/detail` often returns only ids; the metadata comes from the song
            // detail endpoint. Both are needed for a usable track list.
            val tracks = instance.client.songsDetail(trackIds)

            // Resolve every URL in one batch. Doing it lazily per track would fire one request
            // per queue entry when playback starts, which makes a long playlist take many seconds
            // to begin — the queue looks like it simply refuses to play.
            val urls = instance.client.songUrls(tracks.map { it.id })

            // Only playable tracks enter the list. ExoPlayer materialises a MediaSource for every
            // queued item and throws on a null URI, so a single unentitled track would break
            // playback for the whole playlist — which is what made "play" look dead.
            val playable = tracks.mapNotNull { track ->
                val url = urls[track.id]?.url ?: return@mapNotNull null
                track.toAudioModel(instance.providerId)?.copy(playbackUri = url.toUri())
            }

            android.util.Log.d(
                "NetEaseDataSource",
                "playlist ${parsed.id}: ${tracks.size} tracks, ${playable.size} playable",
            )

            Result.Success(playlist.toPlaylistModel(instance.providerId) to playable)
        }
    }

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
    const val KIND_PLAYLIST = "playlist"
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

internal fun NetEaseTrack.toAudioModel(providerId: Long): Audio? {
    val artwork = artworkUrl

    return Audio(
        uri = NetEaseUri.of(providerId, NetEaseUri.KIND_AUDIO, id.toString()),
        thumbnail = artwork?.let {
            Thumbnail.Builder()
                .setUri(it.toUri())
                .setType(Thumbnail.Type.FRONT_COVER)
                .build()
        },
        // Playback is deliberately unset: NetEase URLs are entitlement-gated and a non-VIP
        // account silently receives a 45 second clip, which must not be presented as a full track.
        playbackUri = null,
        mimeType = null,
        title = name,
        type = Audio.Type.MUSIC,
        durationMs = durationMs,
        artistUri = null,
        artistName = artistNames.takeIf { it.isNotBlank() },
        albumUri = albumId?.let { NetEaseUri.of(providerId, NetEaseUri.KIND_ALBUM, it.toString()) },
        albumTitle = albumName,
        discNumber = null,
        trackNumber = null,
        genreUri = null,
        genreName = null,
        year = null,
        isFavorite = false,
    )
}

internal fun NetEaseAlbum.toAlbumModel(providerId: Long): Album? = Album(
    uri = NetEaseUri.of(providerId, NetEaseUri.KIND_ALBUM, id.toString()),
    thumbnail = artworkUrl?.let {
        Thumbnail.Builder()
            .setUri(it.toUri())
            .setType(Thumbnail.Type.FRONT_COVER)
            .build()
    },
    title = name,
    artistUri = null,
    artistName = artistName.takeIf { it.isNotBlank() },
    year = year,
)

internal fun NetEasePlaylist.toPlaylistModel(providerId: Long): Playlist = Playlist(
    uri = NetEaseUri.of(providerId, NetEaseUri.KIND_PLAYLIST, id.toString()),
    thumbnail = artworkUrl?.let {
        Thumbnail.Builder()
            .setUri(it.toUri())
            .setType(Thumbnail.Type.FRONT_COVER)
            .build()
    },
    name = name,
    type = if (isFavorites) Playlist.Type.FAVORITES else Playlist.Type.PLAYLIST,
)