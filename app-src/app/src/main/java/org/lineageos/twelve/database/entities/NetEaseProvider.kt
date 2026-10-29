/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A NetEase Cloud Music account configured in PawAM.
 *
 * NetEase has no OAuth-style API for third-party clients. A session is established by signing in
 * through the web login page and keeping the resulting `MUSIC_U` cookie, which is what the official
 * web client itself uses. [cookie] therefore holds a live session credential.
 *
 * [userId] and [nickname] are cached purely for display so the provider list can show who is signed
 * in without a network round trip; they are refreshed after each sign-in.
 *
 * @param id Local row id.
 * @param name User-visible label for this provider instance.
 * @param cookie The session cookie header value, empty when signed out.
 * @param userId The account id, when known.
 * @param nickname The account display name, when known.
 */
@Entity
data class NetEaseProvider(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "netease_provider_id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cookie") val cookie: String = "",
    @ColumnInfo(name = "user_id") val userId: Long? = null,
    @ColumnInfo(name = "nickname") val nickname: String? = null,
)
