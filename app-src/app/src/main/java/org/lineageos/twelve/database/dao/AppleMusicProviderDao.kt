/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.lineageos.twelve.database.entities.AppleMusicProvider

@Dao
interface AppleMusicProviderDao {
    @Query("SELECT * FROM AppleMusicProvider ORDER BY apple_music_provider_id ASC")
    fun getAll(): Flow<List<AppleMusicProvider>>

    @Query("SELECT * FROM AppleMusicProvider WHERE apple_music_provider_id = :id")
    fun get(id: Long): Flow<AppleMusicProvider?>

    @Insert
    suspend fun insert(provider: AppleMusicProvider): Long

    @Query(
        """
        UPDATE AppleMusicProvider
        SET name = :name,
            developer_token = :developerToken,
            music_user_token = :musicUserToken,
            storefront = :storefront
        WHERE apple_music_provider_id = :id
        """
    )
    suspend fun update(
        id: Long,
        name: String,
        developerToken: String,
        musicUserToken: String,
        storefront: String,
    )

    /**
     * Store only the user token.
     *
     * Signing in through MusicKit yields just this value, and the rest of the row (developer
     * token, storefront, name) must stay untouched.
     */
    @Query(
        """
        UPDATE AppleMusicProvider
        SET music_user_token = :musicUserToken
        WHERE apple_music_provider_id = :id
        """
    )
    suspend fun updateMusicUserToken(id: Long, musicUserToken: String)

    @Query("DELETE FROM AppleMusicProvider WHERE apple_music_provider_id = :id")
    suspend fun delete(id: Long)
}
