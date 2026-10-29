/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.FlowResult.Companion.asFlowResult
import org.lineageos.twelve.models.FlowResult.Companion.foldLatest
import org.lineageos.twelve.models.FlowResult.Companion.getOrNull
import org.lineageos.twelve.models.Playlist

class PlaylistViewModel(application: Application) : TwelveViewModel(application) {
    private val playlistUri = MutableStateFlow<Uri?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlist = playlistUri
        .filterNotNull()
        .flatMapLatest {
            mediaRepository.playlist(it)
        }
        .asFlowResult()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            FlowResult.Loading
        )

    val playlistMetadataCanBeEdited = playlist
        .foldLatest(
            onSuccess = { it.first.type == Playlist.Type.PLAYLIST },
            onError = { _, _ -> false },
        )
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            false
        )

    fun loadPlaylist(playlistUri: Uri) {
        this.playlistUri.value = playlistUri
    }

    suspend fun renamePlaylist(name: String) {
        playlistUri.value?.let { playlistUri ->
            withContext(Dispatchers.IO) {
                mediaRepository.renamePlaylist(playlistUri, name)
            }
        }
    }

    suspend fun deletePlaylist() {
        playlistUri.value?.let { playlistUri ->
            withContext(Dispatchers.IO) {
                mediaRepository.deletePlaylist(playlistUri)
            }
        }
    }

    fun playPlaylist(position: Int = 0) {
        val audios = playlist.value.getOrNull()?.second
        android.util.Log.d(
            "PawAMPlaylist",
            "playPlaylist($position): state=${playlist.value}, tracks=${audios?.size}",
        )

        if (audios.isNullOrEmpty()) {
            return
        }

        // The adapter position can exceed the list when the playlist was refreshed underneath the
        // row that was tapped; clamping keeps the tap useful instead of silently doing nothing.
        playAudio(audios, position.coerceIn(0, audios.lastIndex))
    }

    fun shufflePlayPlaylist() {
        playlist.value.getOrNull()?.second?.takeUnless {
            it.isEmpty()
        }?.let {
            playAudio(it.shuffled(), 0)
        }
    }

    fun addToQueue() {
        playlist.value.getOrNull()?.second?.takeUnless {
            it.isEmpty()
        }?.let {
            addToQueue(it)
        }
    }

    fun playNext() {
        playlist.value.getOrNull()?.second?.takeUnless {
            it.isEmpty()
        }?.let {
            playNext(it)
        }
    }
}
