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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.lineageos.twelve.R
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.applemusic.AppleMusicAlbum
import org.lineageos.twelve.datasources.applemusic.AppleMusicArtist
import org.lineageos.twelve.datasources.applemusic.AppleMusicAudio
import org.lineageos.twelve.datasources.applemusic.AppleMusicClient
import org.lineageos.twelve.datasources.applemusic.AppleMusicCredentials
import org.lineageos.twelve.datasources.applemusic.AppleMusicPlaylist
import org.lineageos.twelve.datasources.applemusic.AppleMusicResult
import org.lineageos.twelve.datasources.applemusic.AppleMusicTokenProvider
import org.lineageos.twelve.datasources.applemusic.AppleMusicUri
import org.lineageos.twelve.datasources.applemusic.toAlbum
import org.lineageos.twelve.datasources.applemusic.toArtist
import org.lineageos.twelve.datasources.applemusic.toAudio
import org.lineageos.twelve.datasources.applemusic.toPlaylist
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
import org.lineageos.twelve.models.ProviderArgument
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.Thumbnail
import org.lineageos.twelve.repositories.ProvidersRepository

/**
 * Apple Music backed data source.
 *
 * ## Scope of this implementation
 *
 * Catalog search, album detail and the signed-in user's library (albums, songs, playlists) are
 * implemented. Playback is deliberately limited to **previews**: a full-length Apple stream is a
 * Widevine-protected HLS manifest that is only playable after acquiring a licence, which is a
 * separate concern. [Audio.playbackUri] therefore carries the preview URL and callers must not
 * present it as full-length playback.
 *
 * Genre browsing and every write operation (playlist mutation, favourites) are reported as
 * [Error.NOT_IMPLEMENTED] rather than silently returning empty data, so the UI can tell the
 * difference between "nothing here" and "not supported yet".
 */
class AppleMusicDataSource(
    coroutineScope: CoroutineScope,
    private val context: android.content.Context,
    private val providersRepository: ProvidersRepository,
    private val database: TwelveDatabase,
    private val cache: okhttp3.Cache? = null,
) : MediaDataSource {
    /**
     * One configured Apple Music account.
     *
     * The credentials are captured per account because the developer token (application identity)
     * and the `media-user-token` (user identity) are independent, and a user may configure several
     * accounts with different storefronts.
     */
    private class AppleMusicInstance(
        val providerId: Long,
        val credentials: AppleMusicCredentials,
        tokenProvider: AppleMusicTokenProvider,
        context: android.content.Context,
        cache: okhttp3.Cache?,
    ) {
        val storefront: String
            get() = credentials.storefront.ifBlank { AppleMusicClient.DEFAULT_STOREFRONT }

        val client = AppleMusicClient(tokenProvider) { credentials }
    }


    /**
     * Resolve the instance for [providerId], or the only configured account when the id is not
     * known.
     *
     * Resolved on demand rather than from a cached state flow: the instances depend on database
     * rows that change after sign-in, and a cached snapshot could stay stale (or empty) depending
     * on subscription timing. A `SharedFlow` with `replay = 1` gives the current provider list
     * synchronously, and the row is read fresh each time.
     */
    /**
     * One token provider per account.
     *
     * The developer token costs a ~5 MB scrape to obtain, so it must be resolved once per account
     * rather than once per in-flight request.
     */
    private val tokenProviders = java.util.concurrent.ConcurrentHashMap<Long, AppleMusicTokenProvider>()

    private suspend fun instanceFor(providerId: Long?): AppleMusicInstance? {
        val allProviders = providersRepository.allProvidersToArguments.first()
            .filter { (provider, _) -> provider.type == ProviderType.APPLE_MUSIC }

        val provider = providerId
            ?.let { id -> allProviders.firstOrNull { it.first.identifier.typeId == id } }
            ?: allProviders.firstOrNull()
            ?: return null

        val typeId = provider.first.identifier.typeId
        val row = database.getAppleMusicProviderDao().get(typeId).firstOrNull()

        val credentials = AppleMusicCredentials(
            developerToken = row?.developerToken.orEmpty(),
            musicUserToken = row?.musicUserToken.orEmpty(),
            storefront = row?.storefront ?: AppleMusicClient.DEFAULT_STOREFRONT,
        )

        val tokenProvider = tokenProviders.getOrPut(typeId) {
            AppleMusicTokenProvider(context) {
                credentials.developerToken.takeIf { it.isNotBlank() }
            }
        }

        return AppleMusicInstance(
            providerId = typeId,
            credentials = credentials,
            tokenProvider = tokenProvider,
            context = context,
            cache = cache,
        )
    }


    private fun <T> AppleMusicResult<T>.toResult(): Result<T, Error> = when (this) {
        is AppleMusicResult.Success -> Result.Success(data)
        is AppleMusicResult.Unauthorized -> Result.Failure(Error.AUTHENTICATION_REQUIRED)
        is AppleMusicResult.NotFound -> Result.Failure(Error.NOT_FOUND)
        is AppleMusicResult.Failed -> Result.Failure(Error.IO, throwable)
    }

    /**
     * Run [block] against the resolved instance and emit its outcome once.
     */
    private fun <T> request(
        providerId: Long?,
        block: suspend (AppleMusicInstance) -> AppleMusicResult<T>,
    ): Flow<Result<T, Error>> = flow {
        val instance = instanceFor(providerId)
            ?: return@flow emit(Result.Failure(Error.AUTHENTICATION_REQUIRED))

        emit(block(instance).toResult())
    }

    // ---------------------------------------------------------------------
    // Provider / type resolution
    // ---------------------------------------------------------------------

    override fun status(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<DataSourceInformation>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val account = instance.client.account()) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    listOf(
                        DataSourceInformation(
                            key = "subscription",
                            keyLocalizedString = localized(R.string.apple_music_subscription),
                            value = LocalizedString.of(account.data.subscriptionActive),
                        ),
                        DataSourceInformation(
                            key = "storefront",
                            keyLocalizedString = localized(R.string.apple_music_storefront),
                            value = LocalizedString.StringLocalizedString(
                                account.data.storefront ?: instance.storefront
                            ),
                        ),
                    )
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> account
            }
        }

    override suspend fun mediaTypeOf(mediaItemUri: Uri): MediaType? {
        val parsed = AppleMusicUri.parse(mediaItemUri) ?: return null

        return when (parsed.kind) {
            AppleMusicUri.KIND_ALBUM -> MediaType.ALBUM
            AppleMusicUri.KIND_ARTIST -> MediaType.ARTIST
            AppleMusicUri.KIND_AUDIO -> MediaType.AUDIO
            AppleMusicUri.KIND_PLAYLIST -> MediaType.PLAYLIST
            else -> null
        }
    }

    override fun providerOf(
        mediaItemUri: Uri,
    ): Flow<MediaRequestStatus<ProviderIdentifier>> {
        val parsed = AppleMusicUri.parse(mediaItemUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return flowOf(
            Result.Success(ProviderIdentifier(ProviderType.APPLE_MUSIC, parsed.providerId))
        )
    }

    // ---------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<ActivityTab>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val albums = instance.client.libraryAlbums(limit = 12)) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    albums.data
                        .map { it.toAlbumModel(instance.providerId, instance.storefront) }
                        .takeIf { it.isNotEmpty() }
                        ?.let { items ->
                            listOf(
                                ActivityTab(
                                    id = "apple_music_library_albums",
                                    title = localized(R.string.apple_music_library_albums),
                                    // ActivityTab stores MediaItem<*>; widen the concrete album
                                    // list explicitly since Kotlin collections are invariant.
                                    items = items.map { it as MediaItem<*> },
                                )
                            )
                        }
                        .orEmpty()
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> albums
            }
        }

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Album>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val albums = instance.client.libraryAlbums()) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    albums.data.map { it.toAlbumModel(instance.providerId, instance.storefront) }
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> albums
            }
        }

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Artist>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val songs = instance.client.librarySongs()) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    songs.data
                        .mapNotNull { audio ->
                            audio.artistName
                                .takeIf { it.isNotBlank() }
                                ?.let { name ->
                                    Artist(
                                        // Apple's library payload does not carry an artist
                                        // relationship, so a search-by-name URI is used rather
                                        // than inventing a catalog id.
                                        uri = AppleMusicUri.of(
                                            instance.providerId,
                                            AppleMusicUri.KIND_ARTIST,
                                            instance.storefront,
                                            name,
                                        ),
                                        thumbnail = null,
                                        name = name,
                                    )
                                }
                        }
                        .distinctBy { it.name }
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> songs
            }
        }

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Audio>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val songs = instance.client.librarySongs()) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    songs.data.mapNotNull {
                        it.toAudioModel(instance.providerId, instance.storefront)
                    }
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> songs
            }
        }

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
            when (val playlists = instance.client.libraryPlaylists()) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    playlists.data.map {
                        it.toPlaylistModel(instance.providerId, instance.storefront)
                    }
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> playlists
            }
        }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ): Flow<MediaRequestStatus<List<MediaItem<*>>>> =
        request(providerIdentifier.typeId) { instance ->
            when (val results = instance.client.search(query)) {
                is AppleMusicResult.Success -> AppleMusicResult.Success(
                    buildList<MediaItem<*>> {
                        addAll(
                            results.data.audios.mapNotNull {
                                it.toAudioModel(instance.providerId, instance.storefront)
                            }
                        )
                        addAll(
                            results.data.albums.map {
                                it.toAlbumModel(instance.providerId, instance.storefront)
                            }
                        )
                        addAll(
                            results.data.artists.map {
                                it.toArtistModel(instance.providerId, instance.storefront)
                            }
                        )
                    }
                )

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> results
            }
        }

    // ---------------------------------------------------------------------
    // Detail
    // ---------------------------------------------------------------------

    override fun audio(audioUri: Uri): Flow<MediaRequestStatus<Audio>> {
        val parsed = AppleMusicUri.parse(audioUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return request(parsed.providerId) { instance ->
            when (val result = instance.client.song(parsed.id)) {
                is AppleMusicResult.Success -> {
                    val audio = result.data.toAudioModel(instance.providerId, parsed.storefront)
                    if (audio != null) {
                        AppleMusicResult.Success(audio)
                    } else {
                        AppleMusicResult.NotFound
                    }
                }

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> result
            }
        }
    }

    override fun album(
        albumUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Album, List<Audio>>>> {
        val parsed = AppleMusicUri.parse(albumUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return request(parsed.providerId) { instance ->
            when (val result = instance.client.album(parsed.id)) {
                is AppleMusicResult.Success -> {
                    val (album, tracks) = result.data
                    AppleMusicResult.Success(
                        album.toAlbumModel(instance.providerId, parsed.storefront) to
                            tracks.mapNotNull {
                                it.toAudioModel(instance.providerId, parsed.storefront)
                            }
                    )
                }

                is AppleMusicResult.Unauthorized -> AppleMusicResult.Unauthorized
                is AppleMusicResult.NotFound -> AppleMusicResult.NotFound
                is AppleMusicResult.Failed -> result
            }
        }
    }

    override fun artist(
        artistUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Artist, ArtistWorks>>> {
        val parsed = AppleMusicUri.parse(artistUri)
            ?: return flowOf(Result.Failure(Error.NOT_FOUND))

        return flow {
            val instance = instanceFor(parsed.providerId)
                ?: return@flow emit(Result.Failure(Error.AUTHENTICATION_REQUIRED))

            emit(
                when (val result = instance.client.artist(parsed.id)) {
                    is AppleMusicResult.Success -> {
                        val (artist, albums) = result.data
                        Result.Success(
                            artist.toArtistModel(instance.providerId, parsed.storefront) to
                                ArtistWorks(
                                    albums = albums.map {
                                        it.toAlbumModel(instance.providerId, parsed.storefront)
                                    },
                                    appearsInAlbum = emptyList(),
                                    appearsInPlaylist = emptyList(),
                                    audios = emptyList(),
                                )
                        )
                    }

                    is AppleMusicResult.Unauthorized ->
                        Result.Failure(Error.AUTHENTICATION_REQUIRED)

                    is AppleMusicResult.NotFound -> Result.Failure(Error.NOT_FOUND)
                    is AppleMusicResult.Failed -> Result.Failure(Error.IO)
                }
            )
        }
    }

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

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

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

    companion object {
        /**
         * Developer token (JWT). Optional: when empty, the token embedded in Apple's web player is
         * scraped as a fallback. Supplying one is strongly preferred — see
         * [org.lineageos.twelve.datasources.applemusic.AppleMusicTokenProvider].
         */
        val ARG_DEVELOPER_TOKEN = ProviderArgument(
            key = "developer_token",
            type = String::class,
            nameStringResId = R.string.apple_music_developer_token,
            required = false,
            hidden = false,
            defaultValue = "",
        )

        /**
         * The `media-user-token` cookie value. Hidden because it is a live session credential.
         */
        val ARG_MEDIA_USER_TOKEN = ProviderArgument(
            key = "media_user_token",
            type = String::class,
            nameStringResId = R.string.apple_music_media_user_token,
            required = false,
            hidden = true,
            defaultValue = "",
        )

        /**
         * ISO storefront code, for example `us`, `cn`, `jp`. Defaults to `us`.
         */
        val ARG_STOREFRONT = ProviderArgument(
            key = "storefront",
            type = String::class,
            nameStringResId = R.string.apple_music_storefront,
            required = false,
            hidden = false,
            defaultValue = AppleMusicClient.DEFAULT_STOREFRONT,
            validate = { value ->
                when (value.matches(Regex("[A-Za-z]{2}"))) {
                    true -> null
                    false -> ProviderArgument.ValidationError(
                        "Storefront must be a two letter country code",
                        R.string.apple_music_storefront_error,
                    )
                }
            },
        )
    }
}

// -------------------------------------------------------------------------
// Model mapping
// -------------------------------------------------------------------------

private fun localized(
    @androidx.annotation.StringRes stringResId: Int,
) = LocalizedString.StringResIdLocalizedString(stringResId)

internal fun AppleMusicAlbum.toAlbumModel(providerId: Long, storefront: String) = Album(
    uri = AppleMusicUri.of(
        providerId,
        AppleMusicUri.KIND_ALBUM,
        storefront,
        ids.catalogId ?: ids.libraryId.orEmpty(),
    ),
    thumbnail = artworkUrl?.let {
        Thumbnail.Builder().setUri(it.toUri()).setType(Thumbnail.Type.FRONT_COVER).build()
    },
    title = title,
    artistUri = null,
    artistName = artistName.takeIf { it.isNotBlank() },
    year = year,
)

internal fun AppleMusicArtist.toArtistModel(providerId: Long, storefront: String) = Artist(
    uri = AppleMusicUri.of(
        providerId,
        AppleMusicUri.KIND_ARTIST,
        storefront,
        ids.catalogId ?: ids.libraryId.orEmpty(),
    ),
    thumbnail = artworkUrl?.let {
        Thumbnail.Builder().setUri(it.toUri()).setType(Thumbnail.Type.FRONT_COVER).build()
    },
    name = name,
)

internal fun AppleMusicAudio.toAudioModel(providerId: Long, storefront: String): Audio? {
    val id = ids.catalogId ?: ids.libraryId ?: return null

    return Audio(
        uri = AppleMusicUri.of(providerId, AppleMusicUri.KIND_AUDIO, storefront, id),
        thumbnail = artworkUrl?.let {
            Thumbnail.Builder().setUri(it.toUri()).setType(Thumbnail.Type.FRONT_COVER).build()
        },
        // Previews only: see the class documentation.
        playbackUri = previewUrl?.toUri(),
        mimeType = "audio/mp4",
        title = title,
        type = Audio.Type.MUSIC,
        durationMs = durationMs,
        artistUri = null,
        artistName = artistName.takeIf { it.isNotBlank() },
        albumUri = albumId?.let { AppleMusicUri.of(providerId, AppleMusicUri.KIND_ALBUM, storefront, it) },
        albumTitle = albumTitle,
        discNumber = discNumber,
        trackNumber = trackNumber,
        genreUri = null,
        genreName = null,
        year = null,
        isFavorite = false,
    )
}

internal fun AppleMusicPlaylist.toPlaylistModel(providerId: Long, storefront: String) = Playlist(
    uri = AppleMusicUri.of(
        providerId,
        AppleMusicUri.KIND_PLAYLIST,
        storefront,
        ids.catalogId ?: ids.libraryId.orEmpty(),
    ),
    thumbnail = artworkUrl?.let {
        Thumbnail.Builder().setUri(it.toUri()).setType(Thumbnail.Type.FRONT_COVER).build()
    },
    name = name,
    type = Playlist.Type.PLAYLIST,
)
