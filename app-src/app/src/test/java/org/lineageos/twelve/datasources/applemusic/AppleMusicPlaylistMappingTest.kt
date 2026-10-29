/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression tests for library playlist mapping.
 *
 * Captured from a real `/v1/me/library/playlists` response: library resources use `p.*` ids,
 * carry no `relationships` block, and **omit `trackCount`**. Any of those being assumed present
 * would make the playlist list silently render as empty.
 */
class AppleMusicPlaylistMappingTest {
    private val libraryPlaylistJson = """
        {
          "id": "p.DV7rO5Kc4RRN0pN",
          "type": "library-playlists",
          "href": "/v1/me/library/playlists/p.DV7rO5Kc4RRN0pN",
          "attributes": {
            "artwork": { "url": "https://example.test/art-{w}x{h}.jpg", "width": 1200, "height": 1200 },
            "canEdit": true,
            "dateAdded": "2025-12-06T11:22:26Z",
            "hasCatalog": true,
            "isPublic": false,
            "lastModifiedDate": "2025-12-06T11:22:26Z",
            "name": "Dreamy",
            "playParams": { "id": "p.DV7rO5Kc4RRN0pN", "isLibrary": true, "kind": "playlist" }
          }
        }
    """.trimIndent()

    @Test
    fun `maps a library playlist that has no track count and no relationships`() {
        val playlist = JSONObject(libraryPlaylistJson).toPlaylist("cn")

        assertNotNull("Library playlists must not be dropped", playlist)

        val mapped = playlist!!
        assertEquals("Dreamy", mapped.name)
        assertEquals("p.DV7rO5Kc4RRN0pN", mapped.ids.libraryId)
        // 0 is acceptable; the server does not send trackCount for library playlists.
        assertEquals(0, mapped.trackCount)
    }

    @Test
    fun `library playlist id is not mistaken for a catalog id`() {
        val ids = JSONObject(libraryPlaylistJson).ids()

        assertEquals("p.DV7rO5Kc4RRN0pN", ids.libraryId)
        // A `p.` id cannot be used to fetch catalog metadata or a playback lease. Treating only
        // `l.`/`i.` as library-scoped used to leave `p.` ids looking like catalog ids.
        assertEquals(null, ids.catalogId)
    }

    @Test
    fun `all library prefixes are recognised`() {
        assertEquals(true, "l.abc".isLibraryScoped())
        assertEquals(true, "i.abc".isLibraryScoped())
        assertEquals(true, "p.abc".isLibraryScoped())
        assertEquals(false, "1440857781".isLibraryScoped())
    }
}
