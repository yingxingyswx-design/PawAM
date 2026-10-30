/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.util.Log
import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import org.lineageos.twelve.TwelveApplication
import org.lineageos.twelve.ext.applicationContext
import org.lineageos.twelve.ext.eventsFlow
import org.lineageos.twelve.ext.resources
import org.lineageos.twelve.ext.shuffleModeEnabled
import org.lineageos.twelve.ext.typedRepeatMode
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.services.PlaybackService

/**
 * Base view model for all app view models.
 * Here we keep the shared stuff every fragment could use, like access to the repository and
 * the media controller to interact with the playback service.
 */
abstract class TwelveViewModel(application: Application) : AndroidViewModel(application) {
    protected val mediaRepository = getApplication<TwelveApplication>().mediaRepository
    protected val providersRepository = getApplication<TwelveApplication>().providersRepository
    protected val outputConfigurationRepository =
        getApplication<TwelveApplication>().outputConfigurationRepository

    @Suppress("EmptyMethod")
    final override fun <T : Application> getApplication() = super.getApplication<T>()

    protected val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(application)!!
    }

    private val sessionToken by lazy {
        SessionToken(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java)
        )
    }

    protected val mediaControllerFlow = channelFlow {
        val mediaController = MediaController.Builder(applicationContext, sessionToken)
            .buildAsync()
            .await()

        trySend(mediaController)

        awaitClose {
            mediaController.release()
        }
    }
        .flowOn(Dispatchers.Main)
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected val mediaController = mediaControllerFlow
        .stateIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    protected val eventsFlow = mediaControllerFlow
        .flatMapLatest { it.eventsFlow() }
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected var shuffleModeEnabled: Boolean
        get() = mediaController.value?.shuffleModeEnabled ?: false
        set(value) {
            mediaController.value?.apply {
                shuffleModeEnabled = value
                sharedPreferences.shuffleModeEnabled = value
            }
        }

    protected var typedRepeatMode: RepeatMode
        get() = mediaController.value?.typedRepeatMode ?: RepeatMode.NONE
        set(value) {
            mediaController.value?.apply {
                typedRepeatMode = value
                sharedPreferences.typedRepeatMode = value
            }
        }

    private val LOG_TAG = "PawAMViewModel"

    fun playAudio(audios: List<Audio>, position: Int) {
        android.util.Log.d(
            "PawAMViewModel",
            "playAudio position=$position size=${audios.size} controller=${mediaController.value != null}",
        )

        mediaController.value?.apply {
            // Initialize shuffle and repeat modes
            shuffleModeEnabled = sharedPreferences.shuffleModeEnabled
            typedRepeatMode = sharedPreferences.typedRepeatMode

            // `setMediaItems(items, resetPosition = true)` leaves the start index unset, so the
            // queue always began at its first item and the tapped row was then "seeked" to by
            // position — the track the user picked was never the one that played. The start index
            // has to be passed explicitly.
            val startIndex = PlaybackStartPosition.indexOf(position, audios.size)

            // `setMediaItems` already installs and prepares the queue. An explicit `prepare()`
            // immediately after it raced the (cross-process) controller command and made the
            // session re-install the playlist with startIndex 0 — which is why the tapped track
            // was never the one that started.
            // The queue must be explicitly started: `setMediaItems` only installs it and leaves the
            // player idle, so without these calls nothing audible happens. They are issued in one
            // batch (the controller coalesces them) so the start index is not lost in between.
            runCatching {
                setMediaItems(audios.map { it.toMedia3MediaItem(resources) }, startIndex, 0L)
                prepare()
                play()
            }.onFailure {
                android.util.Log.e("PawAMViewModel", "setMediaItems/prepare/play failed", it)
            }

            android.util.Log.d(
                "PawAMViewModel",
                "after setMediaItems: count=$mediaItemCount index=$currentMediaItemIndex " +
                    "state=$playbackState commands=${availableCommands.size()}",
            )
        }
    }
    fun addToQueue(audios: List<Audio>) {
        mediaController.value?.apply {
            addMediaItems(audios.map { it.toMedia3MediaItem(resources) })

            if (mediaItemCount == audios.count()) {
                play()
            }
        }
    }

    fun playNext(audios: List<Audio>) {
        mediaController.value?.apply {
            addMediaItems(
                currentMediaItemIndex + 1,
                audios.map { it.toMedia3MediaItem(resources) },
            )

            if (mediaItemCount == audios.count()) {
                play()
            }
        }
    }
}
