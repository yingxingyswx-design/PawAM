/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.Result

/**
 * Library-scoped search for the library screen.
 *
 * Kept separate from `MainViewModel.searchResults`: that flow feeds the global search screen, so
 * reusing it made typing in the library jump away to another screen instead of filtering in place.
 */
class LibrarySearchViewModel(application: Application) : TwelveViewModel(application) {
    private val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<Result<List<MediaItem<*>>, Error>> = query
        .debounce(300)
        .mapLatest { it.trim() }
        .flatMapLatest { term ->
            if (term.isEmpty()) {
                flowOf(Result.Success(emptyList<MediaItem<*>>()))
            } else {
                mediaRepository.search(term)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = Result.Success(emptyList<MediaItem<*>>()),
        )

    fun setQuery(query: String) {
        this.query.value = query
    }
}
