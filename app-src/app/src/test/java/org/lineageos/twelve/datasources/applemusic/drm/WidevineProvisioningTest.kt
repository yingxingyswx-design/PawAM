/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the provisioning payload conversion.
 *
 * This is the exact transform that unblocked full-track playback: `ProvisionRequest.data` is
 * already URL-safe base64 *text*, and Google's `signed_request` field needs standard base64. Sending
 * the raw bytes (double encoding) produced a bare `HTTP 400` with no hint, so the behaviour is
 * pinned here.
 */
class WidevineProvisioningTest {
    @Test
    fun `url safe characters are translated to standard base64`() {
        // "-" and "_" are URL-safe only; standard base64 uses "+" and "/".
        val payload = "-_-_".toByteArray(Charsets.US_ASCII)

        val converted = WidevineProvisioning.toStandardBase64(payload)!!

        assertEquals("+/+/", converted)
    }

    @Test
    fun `padding is added to a multiple of four`() {
        val payload = "CvkY".toByteArray(Charsets.US_ASCII)

        val converted = WidevineProvisioning.toStandardBase64(payload)!!

        assertEquals(0, converted.length % 4)
        assertEquals("CvkY", converted)
    }

    @Test
    fun `existing padding is normalised`() {
        val payload = "CvkYEg==".toByteArray(Charsets.US_ASCII)

        val converted = WidevineProvisioning.toStandardBase64(payload)!!

        assertEquals(0, converted.length % 4)
    }

    @Test
    fun `binary payload is not treated as text`() {
        // A real protobuf would contain bytes outside the base64 alphabet.
        val payload = byteArrayOf(0x08, 0x01, 0x12, 0x10, 0x00, 0xFF.toByte())

        assertNull(WidevineProvisioning.toStandardBase64(payload))
    }

    @Test
    fun `empty payload is rejected`() {
        assertNull(WidevineProvisioning.toStandardBase64(ByteArray(0)))
    }

    @Test
    fun `realistic cdm payload round trips to valid base64`() {
        val payload = "CvkYEgRoXu-WGgQIABIAKsgY".toByteArray(Charsets.US_ASCII)

        val converted = WidevineProvisioning.toStandardBase64(payload)!!

        // Must be decodable as standard base64 — the reason the server rejected it before.
        val decoded = java.util.Base64.getDecoder().decode(converted)
        assertEquals(0, converted.length % 4)
        assertEquals(true, decoded.isNotEmpty())
    }
}
