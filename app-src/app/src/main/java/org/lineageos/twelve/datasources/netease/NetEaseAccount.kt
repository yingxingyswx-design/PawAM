/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.netease

/**
 * The signed-in NetEase account, as far as PawAM needs it for display.
 *
 * @param userId The numeric account id, when the server reports one.
 * @param nickname The display name, when available.
 */
data class NetEaseAccount(
    val userId: Long?,
    val nickname: String?,
)
