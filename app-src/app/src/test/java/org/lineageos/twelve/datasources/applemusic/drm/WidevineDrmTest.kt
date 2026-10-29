/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the three pure pieces of the Widevine path.
 *
 * These are the parts that can be verified without a device or a licence server, and they are
 * exactly where a one-byte mistake produces an opaque `-42585` at runtime.
 */
class WidevineDrmTest {
    /** 16 bytes, matching what Apple puts in the KEY line's data URI. */
    private val keyId = byteArrayOf(
        0x00, 0x00, 0x00, 0x0D, 0x22, 0x84.toByte(), 0x2F, 0x00,
        0x1D, 0xA6.toByte(), 0x37, 0x20, 0x00, 0x2F, 0x86.toByte(), 0x00,
    )

    // ---------------------------------------------------------------------
    // pssh
    // ---------------------------------------------------------------------

    @Test
    fun `cenc header carries AESCTR and the key id`() {
        val header = WidevinePssh.cencHeader(keyId)

        assertEquals(0x08, header[0].toInt())
        assertEquals(0x01, header[1].toInt())
        assertEquals(0x12, header[2].toInt())
        assertEquals(16, header[3].toInt())
        assertEquals(20, header.size)
        assertTrue(keyId.contentEquals(header.copyOfRange(4, 20)))
    }

    @Test
    fun `pssh box layout matches the ISO BMFF structure`() {
        val box = WidevinePssh.buildBox(keyId)

        // size(4) + "pssh"(4) + ver/flags(4) + systemId(16) + dataSize(4) + 20 bytes of protobuf
        assertEquals(52, box.size)

        val declaredSize = ((box[0].toInt() and 0xFF) shl 24) or
            ((box[1].toInt() and 0xFF) shl 16) or
            ((box[2].toInt() and 0xFF) shl 8) or
            (box[3].toInt() and 0xFF)
        assertEquals(52, declaredSize)

        assertEquals("pssh", String(box.copyOfRange(4, 8), Charsets.US_ASCII))
        // version + flags are zero
        assertEquals(0, box[8].toInt())
        assertEquals(0, box[9].toInt())
        assertEquals(0, box[10].toInt())
        assertEquals(0, box[11].toInt())
        assertTrue(WidevinePssh.WIDEVINE_SYSTEM_ID.contentEquals(box.copyOfRange(12, 28)))

        val dataSize = ((box[28].toInt() and 0xFF) shl 24) or
            ((box[29].toInt() and 0xFF) shl 16) or
            ((box[30].toInt() and 0xFF) shl 8) or
            (box[31].toInt() and 0xFF)
        assertEquals(20, dataSize)
    }

    @Test
    fun `key id is decoded from the data uri`() {
        val uri = "data:;base64,AAAAAA0ihC8AHaY3IAAvhg=="

        val decoded = WidevinePssh.keyIdFromDataUri(uri)

        assertEquals(16, decoded!!.size)
        assertEquals(
            "000000000d22842f001da63720002f86",
            decoded.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `malformed data uri yields no key id`() {
        assertNull(WidevinePssh.keyIdFromDataUri("https://example.test/key"))
        assertNull(WidevinePssh.keyIdFromDataUri("data:;base64,AAAA"))
    }

    // ---------------------------------------------------------------------
    // Manifest rewriter
    // ---------------------------------------------------------------------

    @Test
    fun `encrypted manifest is rewritten to CENC with a pssh uri`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,AAAAAA0ihC8AHaY3IAAvhg=="
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="1006@0"
            #EXTINF:9.98,
            seg.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsManifestRewriter.rewrite(manifest)

        assertTrue(result.rewritten)
        assertEquals(
            "data:;base64,AAAAAA0ihC8AHaY3IAAvhg==",
            result.keyUri,
        )
        assertTrue(result.manifest.contains("METHOD=SAMPLE-AES-CENC"))
        assertTrue(result.manifest.contains("KEYFORMAT=\"${WidevinePssh.WIDEVINE_KEYFORMAT}\""))
        assertTrue(result.manifest.contains("data:text/plain;base64,"))
        // The ISO-23001-7 marker must be gone, otherwise Media3 would still ignore the DRM.
        assertTrue(!result.manifest.contains("ISO-23001-7"))
        // Everything else is preserved verbatim.
        assertTrue(result.manifest.contains("seg.m4s"))
        assertTrue(result.manifest.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `unencrypted preview manifest is passed through untouched`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXTINF:30.0,
            preview.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsManifestRewriter.rewrite(manifest)

        assertTrue(!result.rewritten)
        assertNull(result.keyUri)
        assertEquals(manifest, result.manifest.trimEnd('\n'))
    }

    // ---------------------------------------------------------------------
    // Licence request
    // ---------------------------------------------------------------------

    @Test
    fun `licence request carries the mandatory fields`() {
        val body = WidevineLicenseRequest.body(
            challenge = byteArrayOf(1, 2, 3, 4),
            keyUri = "data:;base64,AAAAAA0ihC8AHaY3IAAvhg==",
            adamId = "220365871",
        )

        val json = JSONObject(body)
        assertEquals("AQIDBA==", json.getString("challenge"))
        assertEquals("com.widevine.alpha", json.getString("key-system"))
        // The original KEY URI must be echoed verbatim or Apple rejects the request.
        assertEquals(
            "data:;base64,AAAAAA0ihC8AHaY3IAAvhg==",
            json.getString("uri"),
        )
        assertEquals("220365871", json.getString("adamId"))
        assertEquals(false, json.getBoolean("isLibrary"))
        assertEquals(true, json.getBoolean("user-initiated"))
    }

    @Test
    fun `licence response is parsed and errors are reported as null`() {
        assertEquals(
            listOf<Byte>(9, 8, 7),
            WidevineLicenseRequest.parseResponse("""{"license":"CQgH"}""")!!.toList(),
        )

        assertNull(WidevineLicenseRequest.parseResponse("""{"errorCode":-42585,"status":-1021}"""))
        assertNull(WidevineLicenseRequest.parseResponse("""{"status":0}"""))
        assertNull(WidevineLicenseRequest.parseResponse("not json"))
    }
}
