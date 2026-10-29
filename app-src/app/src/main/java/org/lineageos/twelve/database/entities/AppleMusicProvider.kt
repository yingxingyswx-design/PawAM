/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An Apple Music account configured in PawAM.
 *
 * The two tokens are stored separately on purpose, because they answer different questions and
 * expire independently:
 *
 * - [developerToken] authorises the *application* to call the Apple Music API.
 * - [musicUserToken] identifies the *user* (it is the `media-user-token` cookie value) and is what
 *   lets PawAM read that user's library and request playback.
 *
 * [storefront] is the catalog region (for example `us`, `cn`, `jp`). It is part of request URLs,
 * so a wrong value yields 404s rather than an auth error, which is why it is stored explicitly
 * instead of being inferred per call.
 *
 * @param id Local row id.
 * @param name User-visible label for this provider instance.
 * @param developerToken User supplied developer token, if any.
 * @param musicUserToken Captured `media-user-token`, empty when not signed in.
 * @param storefront ISO storefront code.
 */
@Entity
data class AppleMusicProvider(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "apple_music_provider_id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "developer_token") val developerToken: String = "",
    @ColumnInfo(name = "music_user_token") val musicUserToken: String = "",
    @ColumnInfo(name = "storefront") val storefront: String = "us",
)
