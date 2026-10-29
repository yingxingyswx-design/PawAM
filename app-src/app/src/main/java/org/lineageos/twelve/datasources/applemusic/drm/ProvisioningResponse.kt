/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

/**
 * Extracts the signed provisioning response from Apple's protobuf reply.
 *
 * Apple's `widevineCert` answers with a protobuf `ProvisionResponse`:
 *
 * ```
 * 0A <len> <signed_response bytes>
 * ```
 *
 * The CDM, however, expects the legacy OMA JSON envelope `{"signed_response":"<base64>"}`.
 * Handing it the raw protobuf yields `Provisioning response message is an invalid JSON/base64
 * string`; handing it a JSON wrapper around the **whole** protobuf yields
 * `ExtractAndDecodeSignedMessage: Cannot locate start substring`. The inner field has to be
 * unwrapped first.
 */
object ProvisioningResponse {
    /**
     * Wrap [raw] into the JSON envelope the CDM accepts.
     *
     * If [raw] is already a JSON object it is returned unchanged. If no field 1 is found the
     * original bytes are wrapped as-is, which keeps the failure mode a clear CDM parse error
     * rather than a silent empty response.
     */
    fun toJsonEnvelope(raw: ByteArray): String {
        val text = raw.toString(Charsets.UTF_8).trim()
        if (text.startsWith("{")) {
            return text
        }

        val field = firstLengthDelimitedField(raw) ?: raw
        val encoded = java.util.Base64.getEncoder().encodeToString(field)

        // The CDM parses provisioning replies as OMA DRM XML, not JSON. Wrapping in JSON yields
        // `ExtractAndDecodeSignedMessage: Cannot locate start substring`, which is the CDM failing
        // to find the expected `<SignedResponse>` element.
        return "<ProvisioningResponse xmlns=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<SignedResponse>$encoded</SignedResponse></ProvisioningResponse>"
    }

    /**
     * Read protobuf field number 1 if it is length-delimited (`0A`).
     */
    internal fun firstLengthDelimitedField(raw: ByteArray): ByteArray? {
        if (raw.isEmpty() || (raw[0].toInt() and 0xFF) != 0x0A) {
            return null
        }

        var index = 1
        var length = 0
        var shift = 0

        // Varint length.
        while (index < raw.size) {
            val byte = raw[index].toInt() and 0xFF
            length = length or ((byte and 0x7F) shl shift)
            index++
            shift += 7

            if (byte and 0x80 == 0) {
                break
            }
            if (shift > 28) {
                return null
            }
        }

        if (length <= 0 || index + length > raw.size) {
            return null
        }

        return raw.copyOfRange(index, index + length)
    }
}
