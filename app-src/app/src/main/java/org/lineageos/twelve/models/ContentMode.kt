/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.models

import androidx.annotation.StringRes
import org.lineageos.twelve.R

/**
 * The content ecosystem the app is currently browsing.
 *
 * PawAM serves two separate catalogs. They are **not** interchangeable: each has its own
 * accounts, its own identifiers, its own playback authorization and its own terms of service, so
 * the user picks one mode at a time instead of seeing a merged, ambiguous list.
 *
 * The mode is persisted so the app reopens where the user left it. Apple Music is the default
 * because it is the app's primary target.
 *
 * @param nameStringResId Label shown in the navigation bar.
 * @param providerType The provider type this mode browses, if it has one.
 */
enum class ContentMode(
    @param:StringRes val nameStringResId: Int,
    val providerType: ProviderType?,
) {
    /**
     * Apple Music catalog and library.
     */
    APPLE_MUSIC(
        R.string.main_fragment_section_apple_music,
        ProviderType.APPLE_MUSIC,
    ),

    /**
     * NetEase Cloud Music catalog and library.
     */
    NETEASE(
        R.string.main_fragment_section_netease,
        null,
    );

    /**
     * The mode the navigation item switches to when tapped.
     */
    val other: ContentMode
        get() = when (this) {
            APPLE_MUSIC -> NETEASE
            NETEASE -> APPLE_MUSIC
        }
}
