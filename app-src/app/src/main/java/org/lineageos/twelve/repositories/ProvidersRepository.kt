/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.repositories

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.database.entities.AppleMusicProvider
import org.lineageos.twelve.database.entities.NetEaseProvider
import org.lineageos.twelve.datasources.AmpacheDataSource
import org.lineageos.twelve.datasources.AppleMusicDataSource
import org.lineageos.twelve.datasources.applemusic.AppleMusicClient
import org.lineageos.twelve.datasources.JellyfinDataSource
import org.lineageos.twelve.datasources.MediaStoreDataSource
import org.lineageos.twelve.datasources.SubsonicDataSource
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.SPLIT_LOCAL_DEVICES_KEY
import org.lineageos.twelve.ext.preferenceFlow
import org.lineageos.twelve.ext.splitLocalDevices
import org.lineageos.twelve.ext.storageVolumesFlow
import org.lineageos.twelve.models.Provider
import org.lineageos.twelve.models.ProviderArgument.Companion.getArgument
import org.lineageos.twelve.models.ProviderArgument.Companion.requireArgument
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType

@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersRepository(
    context: Context,
    coroutineScope: CoroutineScope,
    private val database: TwelveDatabase,
) {
    /**
     * Shared preferences.
     */
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // MediaStore
    private val storageManager = context.getSystemService(StorageManager::class.java)

    private val mediaStoreVolumes = storageManager.storageVolumesFlow()
        .mapLatest { storageVolumes ->
            storageVolumes
                .filter { it.state in storageVolumeMountedStates }
                .filter { it.mediaStoreVolumeName != null }
                .sortedBy { it.isPrimary.not() }
        }
        .distinctUntilChanged()

    private val mediaStoreProviders = combine(
        sharedPreferences.preferenceFlow(
            SPLIT_LOCAL_DEVICES_KEY,
            getter = SharedPreferences::splitLocalDevices,
        ),
        mediaStoreVolumes,
    ) { splitLocalDevices, mediaStoreVolumes ->
        buildList {
            when (splitLocalDevices) {
                true -> mediaStoreVolumes.forEach {
                    add(
                        Provider(
                            ProviderType.MEDIASTORE,
                            it.mediaStoreVolumeName.hashCode().toLong(),
                            it.getDescription(context),
                        ) to Bundle {
                            putString(
                                MediaStoreDataSource.ARG_VOLUME_NAME.key,
                                it.mediaStoreVolumeName
                            )
                        }
                    )
                }

                false -> add(
                    Provider(
                        ProviderType.MEDIASTORE,
                        0L,
                        Settings.Global.getString(
                            context.contentResolver,
                            Settings.Global.DEVICE_NAME
                        ) ?: Build.MODEL,
                    ) to Bundle {
                        putString(
                            MediaStoreDataSource.ARG_VOLUME_NAME.key,
                            MediaStore.VOLUME_EXTERNAL
                        )
                    }
                )
            }
        }
    }

    // Subsonic
    private val subsonicProviders = database.getSubsonicProviderDao().getAll()
        .distinctUntilChanged()
        .mapLatest {
            it.map { provider ->
                Provider(
                    ProviderType.SUBSONIC,
                    provider.id,
                    provider.name,
                ) to Bundle {
                    putString(SubsonicDataSource.ARG_SERVER.key, provider.url)
                    putString(SubsonicDataSource.ARG_USERNAME.key, provider.username)
                    putString(SubsonicDataSource.ARG_PASSWORD.key, provider.password)
                    putBoolean(
                        SubsonicDataSource.ARG_USE_LEGACY_AUTHENTICATION.key,
                        provider.useLegacyAuthentication
                    )
                }
            }
        }

    // Jellyfin
    private val jellyfinProviders = database.getJellyfinProviderDao().getAll()
        .distinctUntilChanged()
        .mapLatest {
            it.map { provider ->
                Provider(
                    ProviderType.JELLYFIN,
                    provider.id,
                    provider.name,
                ) to Bundle {
                    putString(JellyfinDataSource.ARG_SERVER.key, provider.url)
                    putString(JellyfinDataSource.ARG_USERNAME.key, provider.username)
                    putString(JellyfinDataSource.ARG_PASSWORD.key, provider.password)
                }
            }
        }

    // Ampache
    private val ampacheProviders = database.getAmpacheProviderDao().getAll()
        .distinctUntilChanged()
        .mapLatest {
            it.map { provider ->
                Provider(
                    ProviderType.AMPACHE,
                    provider.id,
                    provider.name,
                ) to Bundle {
                    putString(AmpacheDataSource.ARG_SERVER.key, provider.url)
                    putString(AmpacheDataSource.ARG_USERNAME.key, provider.username)
                    putString(AmpacheDataSource.ARG_PASSWORD.key, provider.password)
                }
            }
        }

    // Apple Music
    private val appleMusicProviders = database.getAppleMusicProviderDao().getAll()
        .distinctUntilChanged()
        .mapLatest {
            it.map { provider ->
                Provider(
                    ProviderType.APPLE_MUSIC,
                    provider.id,
                    provider.name,
                ) to Bundle {
                    putString(
                        AppleMusicDataSource.ARG_DEVELOPER_TOKEN.key,
                        provider.developerToken,
                    )
                    putString(
                        AppleMusicDataSource.ARG_MEDIA_USER_TOKEN.key,
                        provider.musicUserToken,
                    )
                    putString(AppleMusicDataSource.ARG_STOREFRONT.key, provider.storefront)
                }
            }
        }

    // NetEase
    private val netEaseProviders = database.getNetEaseProviderDao().getAll()
        .distinctUntilChanged()
        .mapLatest {
            it.map { provider ->
                Provider(
                    ProviderType.NETEASE,
                    provider.id,
                    // Prefer the signed-in nickname: it is far more useful in the provider list
                    // than the placeholder name typed before signing in.
                    provider.nickname?.takeIf { nickname -> nickname.isNotBlank() }
                        ?: provider.name,
                ) to Bundle()
            }
        }

    // All providers
    val allProvidersToArguments = combine(
        mediaStoreProviders,
        subsonicProviders,
        jellyfinProviders,
        ampacheProviders,
        appleMusicProviders,
        netEaseProviders,
    ) { it ->
        buildList {
            it.forEach {
                addAll(it)
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            coroutineScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val allProviders = allProvidersToArguments
        .mapLatest { allProvidersToArguments ->
            allProvidersToArguments.map { it.first }
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            coroutineScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    /**
     * Get a flow of the [Provider].
     *
     * @param providerIdentifier The [ProviderIdentifier]
     * @return A flow of the corresponding [Provider].
     */
    fun provider(
        providerIdentifier: ProviderIdentifier
    ) = providerToArguments(providerIdentifier).mapLatest { it?.first }

    /**
     * Get a flow of the [Bundle] containing the arguments. This method should only be used by the
     * provider manager fragment.
     *
     * @param providerIdentifier The [ProviderIdentifier]
     * @return A flow of [Bundle] containing the arguments.
     */
    fun providerArguments(
        providerIdentifier: ProviderIdentifier
    ) = providerToArguments(providerIdentifier).mapLatest { it?.second }

    /**
     * Add a new provider to the database.
     *
     * @param providerType The [ProviderType]
     * @param name The name of the new provider
     * @param arguments The arguments of the new provider. They must have been validated beforehand
     * @return A [Pair] containing the [ProviderType] and the ID of the new provider. You can then
     *   use those values to retrieve the new [Provider]
     */
    suspend fun addProvider(
        providerType: ProviderType, name: String, arguments: Bundle
    ) = when (providerType) {
        ProviderType.MEDIASTORE -> throw Exception("Cannot create MediaStore providers")

        ProviderType.SUBSONIC -> {
            val server = arguments.requireArgument(SubsonicDataSource.ARG_SERVER)
            val username = arguments.requireArgument(SubsonicDataSource.ARG_USERNAME)
            val password = arguments.requireArgument(SubsonicDataSource.ARG_PASSWORD)
            val useLegacyAuthentication = arguments.requireArgument(
                SubsonicDataSource.ARG_USE_LEGACY_AUTHENTICATION
            )

            val typeId = database.getSubsonicProviderDao().create(
                name, server, username, password, useLegacyAuthentication
            )

            providerType to typeId
        }

        ProviderType.JELLYFIN -> {
            val server = arguments.requireArgument(JellyfinDataSource.ARG_SERVER)
            val username = arguments.requireArgument(JellyfinDataSource.ARG_USERNAME)
            val password = arguments.requireArgument(JellyfinDataSource.ARG_PASSWORD)

            val typeId = database.getJellyfinProviderDao().create(
                name, server, username, password
            )

            providerType to typeId
        }

        ProviderType.AMPACHE -> {
            val server = arguments.requireArgument(AmpacheDataSource.ARG_SERVER)
            val username = arguments.requireArgument(AmpacheDataSource.ARG_USERNAME)
            val password = arguments.requireArgument(AmpacheDataSource.ARG_PASSWORD)

            val typeId = database.getAmpacheProviderDao().create(
                name, server, username, password
            )

            providerType to typeId
        }

        ProviderType.APPLE_MUSIC -> {
            val developerToken = arguments
                .getArgument(AppleMusicDataSource.ARG_DEVELOPER_TOKEN).orEmpty()
            val musicUserToken = arguments
                .getArgument(AppleMusicDataSource.ARG_MEDIA_USER_TOKEN).orEmpty()
            val storefront = arguments
                .getArgument(AppleMusicDataSource.ARG_STOREFRONT)
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: AppleMusicClient.DEFAULT_STOREFRONT

            val typeId = database.getAppleMusicProviderDao().insert(
                AppleMusicProvider(
                    id = 0L,
                    name = name,
                    developerToken = developerToken,
                    musicUserToken = musicUserToken,
                    storefront = storefront,
                )
            )

            providerType to typeId
        }

        ProviderType.NETEASE -> {
            val typeId = database.getNetEaseProviderDao().insert(
                NetEaseProvider(
                    id = 0L,
                    name = name,
                )
            )

            providerType to typeId
        }
    }

    /**
     * Update an already existing provider.
     *
     * @param providerIdentifier The [ProviderIdentifier]
     * @param name The updated name
     * @param arguments The updated arguments
     */
    suspend fun updateProvider(
        providerIdentifier: ProviderIdentifier,
        name: String,
        arguments: Bundle
    ) {
        when (providerIdentifier.type) {
            ProviderType.MEDIASTORE -> throw Exception("Cannot update MediaStore providers")

            ProviderType.SUBSONIC -> {
                val server = arguments.requireArgument(SubsonicDataSource.ARG_SERVER)
                val username = arguments.requireArgument(SubsonicDataSource.ARG_USERNAME)
                val password = arguments.requireArgument(SubsonicDataSource.ARG_PASSWORD)
                val useLegacyAuthentication = arguments.requireArgument(
                    SubsonicDataSource.ARG_USE_LEGACY_AUTHENTICATION
                )

                database.getSubsonicProviderDao().update(
                    providerIdentifier.typeId,
                    name,
                    server,
                    username,
                    password,
                    useLegacyAuthentication,
                )
            }

            ProviderType.JELLYFIN -> {
                val server = arguments.requireArgument(JellyfinDataSource.ARG_SERVER)
                val username = arguments.requireArgument(JellyfinDataSource.ARG_USERNAME)
                val password = arguments.requireArgument(JellyfinDataSource.ARG_PASSWORD)

                database.getJellyfinProviderDao().update(
                    providerIdentifier.typeId,
                    name,
                    server,
                    username,
                    password
                )
            }

            ProviderType.AMPACHE -> {
                val server = arguments.requireArgument(AmpacheDataSource.ARG_SERVER)
                val username = arguments.requireArgument(AmpacheDataSource.ARG_USERNAME)
                val password = arguments.requireArgument(AmpacheDataSource.ARG_PASSWORD)

                database.getAmpacheProviderDao().update(
                    providerIdentifier.typeId,
                    name,
                    server,
                    username,
                    password
                )
            }

            ProviderType.APPLE_MUSIC -> {
                val developerToken = arguments
                    .getArgument(AppleMusicDataSource.ARG_DEVELOPER_TOKEN).orEmpty()
                val musicUserToken = arguments
                    .getArgument(AppleMusicDataSource.ARG_MEDIA_USER_TOKEN).orEmpty()
                val storefront = arguments
                    .getArgument(AppleMusicDataSource.ARG_STOREFRONT)
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: AppleMusicClient.DEFAULT_STOREFRONT

                database.getAppleMusicProviderDao().update(
                    providerIdentifier.typeId,
                    name,
                    developerToken,
                    musicUserToken,
                    storefront,
                )
            }

            ProviderType.NETEASE -> database.getNetEaseProviderDao().updateName(
                providerIdentifier.typeId,
                name,
            )
        }
    }

    /**
     * Delete a provider.
     *
     * @param providerIdentifier The [ProviderIdentifier]
     */
    suspend fun deleteProvider(providerIdentifier: ProviderIdentifier) {
        when (providerIdentifier.type) {
            ProviderType.MEDIASTORE -> throw Exception("Cannot delete MediaStore providers")

            ProviderType.SUBSONIC -> database.getSubsonicProviderDao().delete(
                providerIdentifier.typeId
            )

            ProviderType.JELLYFIN -> database.getJellyfinProviderDao().delete(
                providerIdentifier.typeId
            )

            ProviderType.AMPACHE -> database.getAmpacheProviderDao().delete(
                providerIdentifier.typeId
            )

            ProviderType.APPLE_MUSIC -> database.getAppleMusicProviderDao().delete(
                providerIdentifier.typeId
            )

            ProviderType.NETEASE -> database.getNetEaseProviderDao().delete(
                providerIdentifier.typeId
            )
        }
    }

    /**
     * Persist the Music User Token obtained through MusicKit for [providerIdentifier].
     *
     * Only the token column is written: the developer token, storefront and display name belong to
     * the provider configuration and must survive a re-sign-in unchanged.
     */
    suspend fun updateAppleMusicMusicUserToken(
        providerIdentifier: ProviderIdentifier,
        musicUserToken: String,
    ) {
        if (providerIdentifier.type != ProviderType.APPLE_MUSIC) {
            throw IllegalArgumentException("Not an Apple Music provider")
        }

        database.getAppleMusicProviderDao().updateMusicUserToken(
            providerIdentifier.typeId,
            musicUserToken,
        )
    }

    /**
     * Persist the NetEase session obtained from the built-in browser.
     *
     * Only the session columns are written: the display name is owned by the provider
     * configuration and must survive a re-sign-in unchanged.
     */
    suspend fun updateNetEaseSession(
        providerIdentifier: ProviderIdentifier,
        cookie: String,
        userId: Long?,
        nickname: String?,
    ) {
        if (providerIdentifier.type != ProviderType.NETEASE) {
            throw IllegalArgumentException("Not a NetEase provider")
        }

        database.getNetEaseProviderDao().updateSession(
            providerIdentifier.typeId,
            cookie,
            userId,
            nickname,
        )
    }

    /**
     * Persist the storefront discovered during Apple Music sign-in.
     *
     * The storefront selects the catalog region and is part of request URLs, so an account
     * signing in from a different region would otherwise keep hitting the wrong catalog.
     */
    suspend fun updateAppleMusicStorefront(
        providerIdentifier: ProviderIdentifier,
        storefront: String,
    ) {
        if (providerIdentifier.type != ProviderType.APPLE_MUSIC) {
            throw IllegalArgumentException("Not an Apple Music provider")
        }

        database.getAppleMusicProviderDao().updateStorefront(
            providerIdentifier.typeId,
            storefront.lowercase(),
        )
    }

    private fun providerToArguments(
        providerIdentifier: ProviderIdentifier
    ) = allProvidersToArguments.mapLatest {
        it.firstOrNull { (provider, _) ->
            provider.type == providerIdentifier.type && provider.typeId == providerIdentifier.typeId
        }
    }

    companion object {
        /**
         * @see MediaStore.getExternalVolumeNames
         */
        private val storageVolumeMountedStates = arrayOf(
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }
}
