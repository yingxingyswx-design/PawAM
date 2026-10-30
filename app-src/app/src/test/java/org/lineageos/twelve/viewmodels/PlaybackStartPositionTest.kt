/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.telve.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.twelve.viewmodels.PlaybackStartPosition

/**
 * Tests for the mapping from a tapped row to the queue start index used by playback.
 *
 * The two mistakes this guards against are both silent offline: starting at the first track for
 * every tap (`setMediaItems(items, resetPosition = true)`), and passing an out-of-range index, which
 * makes `setMediaItems` throw.
 */
class PlaybackStartPositionTest {
    @Test
    fun `tapped row becomes the start index`() {
        assertEquals(0, PlaybackStartPosition.indexOf(0, 10))
        assertEquals(5, PlaybackStartPosition.indexOf(5, 10))
        assertEquals(9, PlaybackStartPosition.indexOf(9, 10))
    }

    @Test
    fun `index past the end is clamped to the last item`() {
        assertEquals(9, PlaybackStartPosition.indexOf(10, 10))
        assertEquals(9, PlaybackStartPosition.indexOf(999, 10))
    }

    @Test
    fun `negative index falls back to the first item`() {
        assertEquals(0, PlaybackStartPosition.indexOf(-1, 10))
        assertEquals(0, PlaybackStartPosition.indexOf(Int.MIN_VALUE, 10))
    }

    @Test
    fun `single item queue always starts at zero`() {
        assertEquals(0, PlaybackStartPosition.indexOf(0, 1))
        assertEquals(0, PlaybackStartPosition.indexOf(7, 1))
    }

    @Test
    fun `empty queue yields zero instead of throwing`() {
        assertEquals(0, PlaybackStartPosition.indexOf(0, 0))
        assertEquals(0, PlaybackStartPosition.indexOf(3, 0))
    }
}
