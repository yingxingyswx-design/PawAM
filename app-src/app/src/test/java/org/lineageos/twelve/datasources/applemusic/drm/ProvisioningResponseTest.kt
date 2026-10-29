/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for unwrapping Apple's provisioning protobuf.
 *
 * Apple's `widevine-cert-url` answers with a protobuf whose field 1 is the signed response. The
 * length is a varint, so it can span several bytes for payloads over 127 bytes — which is the
 * normal case here (Apple's reply is ~700 bytes). Getting the varint wrong shifts every subsequent
 * byte and produces an opaque CDM parse failure, so the bounds are tested explicitly.
 */
class ProvisioningResponseTest {
    @Test
    fun `extracts a single byte length field`() {
        // 0A 03 <3 bytes>
        val raw = byteArrayOf(0x0A, 0x03, 0x11, 0x22, 0x33)

        val field = ProvisioningResponse.firstLengthDelimitedField(raw)

        assertTrue(field!!.contentEquals(byteArrayOf(0x11, 0x22, 0x33)))
    }

    @Test
    fun `extracts a multi byte varint length`() {
        // 0A B9 01 <185 bytes>: 185 = 0xB9 0x01 as a varint (low 7 bits 0x39 with continuation,
        // then 0x01). Apple's reply is ~700 bytes, so multi-byte lengths are the normal path.
        val payload = ByteArray(185) { (it and 0xFF).toByte() }
        val raw = byteArrayOf(0x0A, 0xB9.toByte(), 0x01) + payload

        val field = ProvisioningResponse.firstLengthDelimitedField(raw)

        assertEquals(185, field!!.size)
        assertTrue(field.contentEquals(payload))
    }

    @Test
    fun `rejects a payload that claims more bytes than exist`() {
        val raw = byteArrayOf(0x0A, 0x10, 0x01, 0x02)

        assertNull(ProvisioningResponse.firstLengthDelimitedField(raw))
    }

    @Test
    fun `rejects a different leading tag`() {
        assertNull(ProvisioningResponse.firstLengthDelimitedField(byteArrayOf(0x08, 0x01)))
    }

    @Test
    fun `handles empty input`() {
        assertNull(ProvisioningResponse.firstLengthDelimitedField(ByteArray(0)))
    }

    @Test
    fun `json envelope passes through untouched`() {
        val json = """{"signed_response":"abc"}"""

        assertEquals(json, ProvisioningResponse.toJsonEnvelope(json.toByteArray()))
    }

    @Test
    fun `protobuf is unwrapped before being wrapped as json`() {
        val payload = byteArrayOf(0x41, 0x42, 0x43)
        val raw = byteArrayOf(0x0A, 0x03) + payload

        val envelope = ProvisioningResponse.toJsonEnvelope(raw)

        // The inner field must be encoded, not the outer protobuf wrapper.
        assertEquals("{\"signed_response\":\"QUJD\"}", envelope)
    }
}
