/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.netease

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NetEase response mapping.
 *
 * NetEase returns two different JSON shapes for the same concept depending on the endpoint:
 * search/playlist responses use `ar`/`al`, while daily recommendations and older endpoints use
 * `artists`/`album`. Both must map to the same model, otherwise a playlist shows artists and the
 * daily shelf does not.
 */
class NetEaseModelsTest {
    @Test
    fun `maps the modern ar and al shape`() {
        val track = JSONObject(
            """
            {
              "id": 1905673990,
              "name": "QZKago Requiem",
              "ar": [ { "name": "t+pazolite" }, { "name": "Guest" } ],
              "al": { "id": 88123, "name": "Some Album", "picUrl": "https://example.test/a.jpg" },
              "dt": 140000,
              "fee": 8
            }
            """.trimIndent()
        ).track()!!

        assertEquals(1905673990L, track.id)
        assertEquals("QZKago Requiem", track.name)
        assertEquals("t+pazolite, Guest", track.artistNames)
        assertEquals(88123L, track.albumId)
        assertEquals("Some Album", track.albumName)
        assertEquals(140000L, track.durationMs)
        assertEquals(8, track.fee)
        assertTrue(!track.isTrial)
    }

    @Test
    fun `maps the legacy artists and album shape used by daily recommendations`() {
        val track = JSONObject(
            """
            {
              "id": 2752280109,
              "name": "S.A.T.E.L.L.I.T.E.",
              "artists": [ { "name": "かめりあ" } ],
              "album": { "id": 5, "name": "Album", "picUrl": "https://example.test/b.jpg" },
              "duration": 297000,
              "fee": 0
            }
            """.trimIndent()
        ).track()!!

        assertEquals("かめりあ", track.artistNames)
        assertEquals(5L, track.albumId)
        // `duration` is a fallback when `dt` is absent.
        assertEquals(297000L, track.durationMs)
    }

    @Test
    fun `detects trial tracks`() {
        val track = JSONObject(
            """
            {
              "id": 1,
              "name": "VIP song",
              "ar": [ { "name": "A" } ],
              "al": { "id": 2, "name": "B" },
              "dt": 1000,
              "fee": 1,
              "freeTrialInfo": { "start": 0, "end": 45 }
            }
            """.trimIndent()
        ).track()!!

        assertTrue(track.isTrial)
        assertEquals(1, track.fee)
    }

    @Test
    fun `tracks without a name or id are dropped`() {
        assertNull(JSONObject("""{ "id": 1 }""").track())
        assertNull(JSONObject("""{ "name": "x" }""").track())
    }

    @Test
    fun `maps a playlist and marks the liked-music special type`() {
        val normal = JSONObject(
            """
            { "id": 10, "name": "paw", "coverImgUrl": "https://example.test/c.jpg", "trackCount": 12 }
            """.trimIndent()
        ).playlist()!!

        assertEquals("paw", normal.name)
        assertEquals(12, normal.trackCount)
        assertTrue(!normal.isFavorites)

        val favorites = JSONObject(
            """{ "id": 11, "name": "喜欢的音乐", "specialType": 5 }"""
        ).playlist()!!

        assertTrue(favorites.isFavorites)
    }

    @Test
    fun `maps an album with artists and publish year`() {
        val album = JSONObject(
            """
            {
              "id": 42,
              "name": "Album",
              "artists": [ { "name": "方大同" } ],
              "picUrl": "https://example.test/d.jpg",
              "size": 1,
              "publishTime": 1149782400000
            }
            """.trimIndent()
        ).album()!!

        assertEquals(42L, album.id)
        assertEquals("方大同", album.artistName)
        assertEquals(1, album.trackCount)
        assertNotNull(album.year)
        assertEquals(3, album.year!! - 2003) // 2006
    }

    @Test
    fun `null json arrays are tolerated`() {
        // Responses omit arrays entirely for empty collections; mapping must not throw.
        assertTrue(JSONObject("""{ "id": 1, "name": "x" }""").album()!!.artistName.isEmpty())
    }
}
