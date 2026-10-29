/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

/**
 * Sends a Widevine provisioning request to Google's certificate service.
 *
 * ## The encoding trap
 *
 * `ExoMediaDrm.ProvisionRequest.data` is **not** raw protobuf: it is the agreed provisioning
 * payload already encoded as **URL-safe base64 text**. Base64-encoding it again produces a payload
 * the service cannot decode, and the only feedback is a bare `HTTP 400`.
 *
 * Google's endpoint declares `signed_request` as `TYPE_BYTES`, whose JSON form must be **standard**
 * base64, so the URL-safe characters (`-`, `_`) have to be translated and the string re-padded.
 */
object WidevineProvisioning {
    /** One encoding attempt and the server's verdict. */
    data class Attempt(
        val name: String,
        val code: Int,
        val bodySize: Int,
        val preview: String,
    )

    /**
     * Convert the CDM's URL-safe base64 payload into the standard base64 Google expects.
     *
     * Returns null when [data] is not printable base64 text, in which case it is raw bytes and
     * should be encoded normally.
     */
    fun toStandardBase64(data: ByteArray): String? {
        val text = runCatching { data.toString(Charsets.US_ASCII) }.getOrNull() ?: return null

        if (text.isEmpty() || text.any { it !in BASE64_URL_ALPHABET }) {
            return null
        }

        val standard = text.replace('-', '+').replace('_', '/').trimEnd('=')
        val padding = (4 - standard.length % 4) % 4
        return standard + "=".repeat(padding)
    }

    /**
     * Ask the service for a device certificate.
     *
     * @return The certificate bytes, or null when every encoding was rejected.
     */
    fun requestCertificate(
        client: OkHttpClient,
        url: String,
        provisionRequest: ByteArray,
    ): Pair<ByteArray?, List<Attempt>> {
        val payload = toStandardBase64(provisionRequest)
        val attempts = mutableListOf<Attempt>()

        // Preferred first: the payload is already base64 text, so it is sent as-is (converted to
        // standard base64). The remaining entries exist so one run reports which encoding the
        // service accepts instead of requiring a rebuild per guess.
        val variants = buildList {
            if (payload != null) {
                add("json-ascii" to ("application/json" to JSONObject().put("signed_request", payload).toString()))
            }
            add(
                "json-double" to (
                    "application/json" to JSONObject()
                        .put("signed_request", Base64.getEncoder().encodeToString(provisionRequest))
                        .toString()
                    )
            )
            add(
                "raw" to ("application/octet-stream" to null)
            )
        }

        for ((name, spec) in variants) {
            val (contentType, body) = spec
            val requestBody = if (body == null) {
                provisionRequest.toRequestBody(contentType.toMediaType())
            } else {
                body.toRequestBody(contentType.toMediaType())
            }

            val result = runCatching {
                client.newCall(Request.Builder().url(url).post(requestBody).build())
                    .execute()
                    .use { response ->
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        Triple(response.code, bytes, previewOf(bytes))
                    }
            }.getOrNull() ?: Triple(-1, ByteArray(0), "request failed")

            val (code, bytes, preview) = result
            attempts += Attempt(name, code, bytes.size, preview)

            if (code == 200 && bytes.isNotEmpty()) {
                return bytes to attempts
            }
        }

        return null to attempts
    }

    private fun previewOf(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        val printable = text.filter { it.code in 32..126 }
        return if (printable.length >= text.length / 2 && printable.isNotEmpty()) {
            text.take(200)
        } else {
            bytes.take(24).joinToString("") { "%02x".format(it) }
        }
    }

    private const val BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="
}
