/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Apple Music resource mapping and item URIs.
 *
 * These cover the two places where this integration is easiest to get wrong:
 *
 * 1. **Catalog id vs library id.** A library resource answers with `l.*`/`i.*` ids that cannot be
 *    used for catalog lookups or playback; the real Adam ID only arrives through the
 *    `relationships.catalog` include.
 * 2. **URI round-tripping.** Every persisted reference (queue, resumption playlist, favourites)
 *    is a URI string, so an item must survive being turned into a URI and back.
 */
class AppleMusicModelsTest {
    // ---------------------------------------------------------------------
    // Item URIs
    // ---------------------------------------------------------------------

    @Test
    fun `item uri round trips including the provider id`() {
        val uri = AppleMusicUri.of(
            providerId = 7L,
            kind = AppleMusicUri.KIND_ALBUM,
            storefront = "cn",
            id = "1440857781",
        )

        val parsed = AppleMusicUri.parse(uri)!!

        assertEquals(7L, parsed.providerId)
        assertEquals(AppleMusicUri.KIND_ALBUM, parsed.kind)
        assertEquals("cn", parsed.storefront)
        assertEquals("1440857781", parsed.id)
    }

    @Test
    fun `item uri keeps two providers apart`() {
        val first = AppleMusicUri.of(1L, AppleMusicUri.KIND_AUDIO, "us", "100")
        val second = AppleMusicUri.of(2L, AppleMusicUri.KIND_AUDIO, "us", "100")

        // Same catalog id, different account: these must not collide.
        assertTrue(first != second)
        assertEquals(1L, AppleMusicUri.parse(first)!!.providerId)
        assertEquals(2L, AppleMusicUri.parse(second)!!.providerId)
    }

    @Test
    fun `foreign uris are rejected`() {
        assertNull(AppleMusicUri.parse(Uri.parse("content://media/audio/external/media/1")))
        assertNull(AppleMusicUri.parse(Uri.parse("applemusic://catalog/notanumber/album/us/1")))
        assertNull(AppleMusicUri.parse(Uri.parse("applemusic://catalog/1/album/us")))
    }

    @Test
    fun `blank storefront falls back to the default`() {
        val uri = AppleMusicUri.of(1L, AppleMusicUri.KIND_AUDIO, "", "42")

        assertEquals(AppleMusicClient.DEFAULT_STOREFRONT, AppleMusicUri.parse(uri)!!.storefront)
    }

    // ---------------------------------------------------------------------
    // Id resolution
    // ---------------------------------------------------------------------

    @Test
    fun `catalog resource uses its own id`() {
        val ids = JSONObject(
            """
            { "id": "1440857781", "type": "albums", "attributes": { "name": "Album" } }
            """.trimIndent()
        ).ids()

        assertEquals("1440857781", ids.catalogId)
        assertNull(ids.libraryId)
        assertTrue(ids.isPlayable)
    }

    @Test
    fun `library resource resolves the catalog id through the relationship`() {
        val ids = JSONObject(
            """
            {
              "id": "l.abc123",
              "type": "library-albums",
              "attributes": { "name": "Library Album" },
              "relationships": {
                "catalog": {
                  "data": [ { "id": "1440857781", "type": "albums" } ]
                }
              }
            }
            """.trimIndent()
        ).ids()

        assertEquals("1440857781", ids.catalogId)
        assertEquals("l.abc123", ids.libraryId)
        assertTrue(ids.isPlayable)
    }

    @Test
    fun `library resource without a catalog relationship is not playable`() {
        val ids = JSONObject(
            """
            { "id": "l.abc123", "type": "library-albums", "attributes": { "name": "X" } }
            """.trimIndent()
        ).ids()

        assertNull(ids.catalogId)
        assertEquals("l.abc123", ids.libraryId)
        assertTrue(!ids.isPlayable)
    }

    // ---------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------

    @Test
    fun `maps a song with preview and album`() {
        val audio = JSONObject(SONG_JSON).toAudio("us")!!

        assertEquals("1440857781", audio.ids.catalogId)
        assertEquals("After Hours", audio.title)
        assertEquals("The Weeknd", audio.artistName)
        assertEquals("After Hours", audio.albumTitle)
        // Duration is read from durationInMillis, not seconds.
        assertEquals(361_000L, audio.durationMs)
        assertEquals(5, audio.trackNumber)
        assertEquals(1, audio.discNumber)
        assertEquals("https://example.test/preview.m4a", audio.previewUrl)
        assertTrue(audio.isExplicit)
    }

    @Test
    fun `artwork url placeholders are substituted`() {
        val audio = JSONObject(SONG_JSON).toAudio("us")!!

        assertEquals(
            "https://example.test/art-632x632.jpg",
            audio.artworkUrl,
        )
    }

    @Test
    fun `album release date yields a year`() {
        val album = JSONObject(
            """
            {
              "id": "1440857781",
              "type": "albums",
              "attributes": {
                "name": "After Hours",
                "artistName": "The Weeknd",
                "releaseDate": "2020-03-20",
                "trackCount": 14,
                "artwork": { "url": "https://example.test/art-{w}x{h}.jpg" }
              }
            }
            """.trimIndent()
        ).toAlbum("us")!!

        assertEquals(2020, album.year)
        assertEquals(14, album.trackCount)
        assertEquals("2020-03-20", album.releaseDate)
    }

    @Test
    fun `song without a name is dropped`() {
        assertNull(JSONObject("""{ "id": "1", "attributes": { } }""").toAudio("us"))
    }

    private companion object {
        val SONG_JSON = """
            {
              "id": "1440857781",
              "type": "songs",
              "attributes": {
                "name": "After Hours",
                "artistName": "The Weeknd",
                "albumName": "After Hours",
                "durationInMillis": 361000,
                "trackNumber": 5,
                "discNumber": 1,
                "contentRating": "explicit",
                "artwork": { "url": "https://example.test/art-{w}x{h}.jpg" },
                "playParams": { "catalogId": "1440857781" },
                "previews": [ { "url": "https://example.test/preview.m4a" } ]
              }
            }
        """.trimIndent()
    }
}
