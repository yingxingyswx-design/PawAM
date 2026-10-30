/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.ContentMode
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.FlowResult.Companion.asFlowResult
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.Result.Companion.map
import org.lineageos.twelve.ext.CONTENT_MODE_KEY
import org.lineageos.twelve.ext.contentMode
import org.lineageos.twelve.ext.preferenceFlow

/**
 * Home page view model.
 */
class MainViewModel(application: Application) : TwelveViewModel(application) {
    val navigationProvider = mediaRepository.navigationProvider
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            null,
        )

    private val searchQuery = MutableStateFlow("" to false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults = searchQuery
        .mapLatest {
            val (query, immediate) = it
            if (!immediate && query.isNotEmpty()) {
                delay(500)
            }
            query
        }
        .flatMapLatest { query ->
            query.trim().takeIf { it.isNotEmpty() }?.let {
                mediaRepository.search(it)
            } ?: flowOf(Result.Success(listOf()))
        }
        .asFlowResult()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            FlowResult.Loading
        )

    /**
     * The content ecosystem currently being browsed (Apple Music or NetEase).
     *
     * Backed by a preference so the choice survives process death, and exposed as a flow so the
     * navigation bar label stays in sync without the fragment owning the state.
     */
    val contentMode = sharedPreferences.preferenceFlow(
        CONTENT_MODE_KEY,
        getter = { contentMode },
    )
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            sharedPreferences.contentMode,
        )

    /**
     * Switch to the other content mode.
     */
    fun toggleContentMode() = setContentMode(sharedPreferences.contentMode.other)

    /**
     * Switch to [mode] and point browsing at that mode's provider when one is configured.
     *
     * Switching the navigation provider is what actually changes which catalog the library,
     * activity and search screens read from. When the target mode has no provider yet (for
     * example NetEase before an account is added) the current provider is left alone rather than
     * being forced to an invalid value.
     */
    fun setContentMode(mode: ContentMode) {
        sharedPreferences.contentMode = mode

        val providerType = mode.providerType ?: return
        viewModelScope.launch {
            providersRepository.allProviders.first()
                .firstOrNull { it.type == providerType }
                ?.let { mediaRepository.setNavigationProvider(it.identifier) }
        }
    }

    fun setSearchQuery(query: String, immediate: Boolean = false) {
        searchQuery.value = query to immediate
    }

    suspend fun playAllAudios() = mediaRepository.audios().firstOrNull()?.map { audios ->
        playAudio(audios.shuffled(), 0)
    } ?: Result.Failure(Error.INVALID_RESPONSE)
}
