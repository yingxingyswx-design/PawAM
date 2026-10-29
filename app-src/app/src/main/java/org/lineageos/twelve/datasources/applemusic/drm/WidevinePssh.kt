/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import java.util.Base64

/**
 * Minimal Widevine pssh box builder.
 *
 * Apple's HLS manifest declares encryption as `METHOD=ISO-23001-7` whose key URI is a `data:`
 * URI holding **only the 16-byte key id**. Media3/Widevine cannot use that directly: the licence
 * server validates a `pssh` box whose payload is a `WidevineCencHeader` protobuf carrying the
 * algorithm and the key id. Wrapping the raw 16 bytes is not enough — Apple answers
 * `errorCode -42585`.
 *
 * Protobuf layout produced here:
 *
 * ```
 * WidevineCencHeader {
 *   algorithm = AESCTR   // field 1, varint, value 1  -> 08 01
 *   key_id    = <16 B>   // field 2, length-delimited -> 12 10 <kid>
 * }
 * ```
 */
object WidevinePssh {
    /** Widevine DRM system id, as used in the pssh box and in Media3's scheme UUID. */
    val WIDEVINE_SYSTEM_ID = byteArrayOf(
        0xED.toByte(), 0xEF.toByte(), 0x8B.toByte(), 0xA9.toByte(),
        0x79.toByte(), 0xD6.toByte(), 0x4A.toByte(), 0xCE.toByte(),
        0xA3.toByte(), 0xC8.toByte(), 0x27.toByte(), 0xDC.toByte(),
        0xD5.toByte(), 0x1D.toByte(), 0x21.toByte(), 0xED.toByte(),
    )

    /** `KEYFORMAT` value Media3 recognises as Widevine. */
    const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    /**
     * Build the `WidevineCencHeader` protobuf carrying [keyId].
     */
    fun cencHeader(keyId: ByteArray): ByteArray {
        require(keyId.isNotEmpty()) { "key id must not be empty" }

        val header = ByteArray(2 + 2 + keyId.size)
        var i = 0
        header[i++] = 0x08 // field 1, varint
        header[i++] = 0x01 // algorithm = AESCTR
        header[i++] = 0x12 // field 2, length-delimited
        header[i++] = keyId.size.toByte()
        keyId.copyInto(header, i)
        return header
    }

    /**
     * Build a complete `pssh` box for [keyId].
     */
    fun buildBox(keyId: ByteArray): ByteArray {
        val data = cencHeader(keyId)
        // size(4) + "pssh"(4) + version/flags(4) + systemId(16) + dataSize(4) + data
        val size = 4 + 4 + 4 + 16 + 4 + data.size

        val box = ByteArray(size)
        var i = 0

        box[i++] = (size ushr 24).toByte()
        box[i++] = (size ushr 16).toByte()
        box[i++] = (size ushr 8).toByte()
        box[i++] = size.toByte()

        for (b in "pssh".toByteArray(Charsets.US_ASCII)) {
            box[i++] = b
        }

        // version 0, flags 0
        i += 3
        box[i++] = 0

        WIDEVINE_SYSTEM_ID.copyInto(box, i)
        i += WIDEVINE_SYSTEM_ID.size

        box[i++] = (data.size ushr 24).toByte()
        box[i++] = (data.size ushr 16).toByte()
        box[i++] = (data.size ushr 8).toByte()
        box[i] = data.size.toByte()
        i += 1

        data.copyInto(box, i)
        return box
    }

    /**
     * Decode the key id from a `data:...;base64,<kid>` URI.
     *
     * Returns null when the URI is not a data URI or the payload is not 16 bytes.
     */
    fun keyIdFromDataUri(uri: String): ByteArray? {
        val marker = uri.indexOf("base64,")
        if (marker < 0) {
            return null
        }

        val payload = uri.substring(marker + "base64,".length).trim()
        val decoded = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        return decoded.takeIf { it.size == 16 }
    }

    /**
     * Wrap [box] into the `data:` URI form Media3 accepts as DRM initialisation data.
     */
    fun boxToDataUri(box: ByteArray): String =
        "data:text/plain;base64," + Base64.getEncoder().encodeToString(box)
}
