/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.lineageos.twelve.database.entities.NetEaseProvider

@Dao
interface NetEaseProviderDao {
    @Query("SELECT * FROM NetEaseProvider ORDER BY netease_provider_id ASC")
    fun getAll(): Flow<List<NetEaseProvider>>

    @Query("SELECT * FROM NetEaseProvider WHERE netease_provider_id = :id")
    fun get(id: Long): Flow<NetEaseProvider?>

    @Insert
    suspend fun insert(provider: NetEaseProvider): Long

    @Query("UPDATE NetEaseProvider SET name = :name WHERE netease_provider_id = :id")
    suspend fun updateName(id: Long, name: String)

    /**
     * Store the session obtained from the web login.
     *
     * Only the session columns are written so renaming a provider and signing in do not clobber
     * each other.
     */
    @Query(
        """
        UPDATE NetEaseProvider
        SET cookie = :cookie,
            user_id = :userId,
            nickname = :nickname
        WHERE netease_provider_id = :id
        """
    )
    suspend fun updateSession(
        id: Long,
        cookie: String,
        userId: Long?,
        nickname: String?,
    ): Int

    @Query("DELETE FROM NetEaseProvider WHERE netease_provider_id = :id")
    suspend fun delete(id: Long)
}
