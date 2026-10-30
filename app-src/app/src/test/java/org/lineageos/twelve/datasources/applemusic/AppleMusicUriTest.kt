/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip tests for the PawAM URI scheme's pure logic.
 *
 * Every Apple entry is addressed by one of these URIs, and a playlist created from the UI is
 * addressed by the id Apple returns. Library ids carry a `p.` (or `l.`) prefix, so a parser that
 * splits or trims on '.' breaks navigation and playback in a way that is easy to miss.
 *
 * `android.net.Uri` is not available in JVM tests, so [AppleMusicUri.parse] delegates to the
 * pure overload tested here.
 */
class AppleMusicUriTest {
    @Test
    fun `audio uri round trips`() {
        val segments = AppleMusicUri.pathSegments(1L, AppleMusicUri.KIND_AUDIO, "cn", "1440857781")

        assertEquals(listOf("1", "audio", "cn", "1440857781"), segments)

        val parsed = AppleMusicUri.parse(AppleMusicUri.SCHEME, AppleMusicUri.AUTHORITY, segments)!!

        assertEquals(1L, parsed.providerId)
        assertEquals(AppleMusicUri.KIND_AUDIO, parsed.kind)
        assertEquals("cn", parsed.storefront)
        assertEquals("1440857781", parsed.id)
    }

    @Test
    fun `library playlist id keeps its prefix`() {
        val id = "p.abcDEF123456"

        val segments = AppleMusicUri.pathSegments(7L, AppleMusicUri.KIND_PLAYLIST, "us", id)
        val parsed = AppleMusicUri.parse(AppleMusicUri.SCHEME, AppleMusicUri.AUTHORITY, segments)!!

        assertEquals(id, parsed.id)
        assertEquals(AppleMusicUri.KIND_PLAYLIST, parsed.kind)
        assertEquals("us", parsed.storefront)
        assertEquals(7L, parsed.providerId)
    }

    @Test
    fun `library album id keeps its prefix`() {
        val parsed = AppleMusicUri.parse(
            AppleMusicUri.SCHEME,
            AppleMusicUri.AUTHORITY,
            AppleMusicUri.pathSegments(2L, AppleMusicUri.KIND_ALBUM, "cn", "l.XyZ789"),
        )!!

        assertEquals("l.XyZ789", parsed.id)
    }

    @Test
    fun `ids containing a dot survive parsing`() {
        // Section counting must not split on '.' inside the id itself.
        val id = "p.a.b.c"

        val parsed = AppleMusicUri.parse(
            AppleMusicUri.SCHEME,
            AppleMusicUri.AUTHORITY,
            AppleMusicUri.pathSegments(3L, AppleMusicUri.KIND_PLAYLIST, "jp", id),
        )!!

        assertEquals(id, parsed.id)
    }

    @Test
    fun `blank storefront falls back to the default`() {
        val segments = AppleMusicUri.pathSegments(1L, AppleMusicUri.KIND_AUDIO, "", "1")

        assertEquals(AppleMusicClient.DEFAULT_STOREFRONT, segments[2])
    }

    @Test
    fun `foreign schemes are rejected`() {
        assertNull(AppleMusicUri.parse("https", "music.apple.com", listOf("cn", "album", "1", "2")))
        assertNull(AppleMusicUri.parse("netease", "catalog", listOf("1", "audio", "cn", "2")))
    }

    @Test
    fun `wrong segment count is rejected`() {
        assertNull(AppleMusicUri.parse(AppleMusicUri.SCHEME, AppleMusicUri.AUTHORITY, listOf("1", "audio")))
        assertNull(
            AppleMusicUri.parse(
                AppleMusicUri.SCHEME,
                AppleMusicUri.AUTHORITY,
                listOf("1", "audio", "cn", "id", "extra"),
            )
        )
    }

    @Test
    fun `non numeric provider id is rejected`() {
        assertNull(
            AppleMusicUri.parse(
                AppleMusicUri.SCHEME,
                AppleMusicUri.AUTHORITY,
                listOf("abc", "audio", "cn", "1"),
            )
        )
    }
}
