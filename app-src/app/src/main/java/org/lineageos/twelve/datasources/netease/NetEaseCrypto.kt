/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.netease

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase Cloud Music "eapi" request envelope.
 *
 * The eapi protocol wraps a JSON payload in a signed string and encrypts it with AES-128-ECB.
 * The plaintext is built as
 *
 * ```
 * "nobody" + <path> + "use" + <json> + "md5forencrypt"
 * signature = md5(the above)
 * params    = <path> + "-36cd479b6b5-" + <json> + "-36cd479b6b5-" + <signature>
 * ```
 *
 * and `params` is then AES-128-ECB/PKCS5 encrypted and hex-encoded, to be sent as the single
 * `params` form field of a POST to the `/eapi/...` variant of `path`.
 *
 * Implemented from the publicly documented protocol. Reference implementations and the
 * protocol write-up this was cross-checked against are credited in `docs/THIRD_PARTY.md`.
 */
object NetEaseCrypto {
    /**
     * Fixed protocol key. Unlike a user secret this is a wire-format constant shared by every
     * eapi client; it is not an account credential and grants no access on its own.
     */
    private const val AES_KEY = "e82ckenh8dichen8"

    private const val DIGEST_PREFIX = "nobody"
    private const val DIGEST_SUFFIX = "md5forencrypt"
    private const val PARTS_SEPARATOR = "-36cd479b6b5-"

    /**
     * Build the `params` field for [url] and [payload].
     *
     * @param url The full request URL. Its `/eapi/` segment is normalised to `/api/` because the
     *   signature is always computed over the *logical* API path, not the encrypted endpoint.
     * @param payload The request body to encrypt.
     * @return The hex-encoded, AES-encrypted `params` value.
     */
    fun encryptParams(url: String, payload: String): String {
        val urlPath = apiPathOf(url)

        val signature = md5("$DIGEST_PREFIX$urlPath" + "use" + payload + DIGEST_SUFFIX)
        val plain = urlPath + PARTS_SEPARATOR + payload + PARTS_SEPARATOR + signature

        return aesEcbEncryptHex(plain)
    }

    /**
     * Normalise a request URL to the logical API path used in the signature.
     */
    fun apiPathOf(url: String): String {
        val path = runCatching { java.net.URI(url).path }.getOrNull()
            ?: url.substringAfter("://").substringAfter('/', "")
            .let { "/$it" }

        return path.replace("/eapi/", "/api/")
    }

    /**
     * Decrypt an eapi response body.
     *
     * A few endpoints (notably writes such as liking a track) answer with an AES-ECB encrypted
     * JSON document instead of plain JSON. Returns null when [body] is not such a document, which
     * lets callers fall back to plain parsing.
     */
    fun decryptResponse(body: String): String? {
        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(body.trim())
        }.getOrNull() ?: return null

        if (bytes.isEmpty() || bytes.size % AES_BLOCK_SIZE != 0) {
            return null
        }

        return runCatching {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"))
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        }.getOrNull()
            ?.takeIf { it.trimStart().startsWith('{') }
            ?.trimEnd()
    }

    private fun aesEcbEncryptHex(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"))
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private const val AES_BLOCK_SIZE = 16
    private const val HEX_DIGITS = "0123456789abcdef"
}
