/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import org.lineageos.twelve.datasources.applemusic.AppleMusicHttp
import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.MainScope
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.repositories.MediaRepository
import org.lineageos.twelve.repositories.OutputConfigurationRepository
import org.lineageos.twelve.repositories.ProvidersRepository
import org.lineageos.twelve.repositories.ResumptionPlaylistRepository
import org.lineageos.twelve.ui.coil.ThumbnailMapper

@androidx.annotation.OptIn(UnstableApi::class)
class TwelveApplication : Application(), SingletonImageLoader.Factory {
    private val coroutineScope = MainScope()
    private val database by lazy { TwelveDatabase.get(applicationContext) }
    val providersRepository by lazy {
        ProvidersRepository(applicationContext, coroutineScope, database)
    }
    val mediaRepository by lazy {
        MediaRepository(applicationContext, coroutineScope, providersRepository, database)
    }
    val resumptionPlaylistRepository by lazy { ResumptionPlaylistRepository(database) }
    val outputConfigurationRepository by lazy { OutputConfigurationRepository() }

    override fun onCreate() {
        super.onCreate()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Keep library metadata on disk. Album, song and playlist listings are plain JSON that
        // rarely changes, so a shared cache makes the library open immediately and stay usable on
        // a weak connection instead of blocking on a round trip every time.
        AppleMusicHttp.cache = okhttp3.Cache(
            java.io.File(cacheDir, "applemusic_http"),
            APPLE_MUSIC_CACHE_BYTES,
        )
    }

    private companion object {
        private const val APPLE_MUSIC_CACHE_BYTES = 512L * 1024 * 1024
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(this)
        .components {
            add(ThumbnailMapper)
        }
        .build()
}
