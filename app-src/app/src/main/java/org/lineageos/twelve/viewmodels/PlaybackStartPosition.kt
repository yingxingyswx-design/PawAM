/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

/**
 * Maps "the row the user tapped" to "the index playback should start at".
 *
 * Media3 offers two ways to install a queue and they are easy to confuse:
 *
 * - `setMediaItems(items, resetPosition)` — the boolean resets playback to the default position of
 *   the **first** window. It does not select a track.
 * - `setMediaItems(items, startIndex, startPositionMs)` — this is the one that starts at a
 *   specific item.
 *
 * Using the first and then calling `seekToDefaultPosition(position)` sets the position *within the
 * first track*, so tapping any row played track 0. Passing an index outside the queue to the second
 * overload throws instead.
 *
 * Kept free of Android types so the mapping can be unit tested.
 */
object PlaybackStartPosition {
    /**
     * Clamp [position] to a valid index for a queue of [size] items.
     */
    fun indexOf(position: Int, size: Int): Int =
        if (size <= 0) 0 else position.coerceIn(0, size - 1)
}
