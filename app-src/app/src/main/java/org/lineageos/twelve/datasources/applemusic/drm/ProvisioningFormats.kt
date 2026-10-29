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
 * Tries the provisioning request encodings Google's certificate service may expect.
 *
 * The service answers `HTTP 400` for a body it cannot parse, which is indistinguishable from a
 * body it parsed but rejected. Rather than rebuilding the app once per guess, every plausible
 * encoding is attempted in a single pass and the outcome of each is logged, so one device run
 * identifies the correct one.
 */
object ProvisioningFormats {
    /** One attempt and what the server said about it. */
    data class Attempt(
        val name: String,
        val code: Int,
        val bodySize: Int,
        val bodyPreview: String,
    ) {
        val succeeded: Boolean get() = code == 200 && bodySize > 0
    }

    /**
     * Attempt every format, returning the first success and the log of all attempts.
     */
    fun tryAll(
        client: OkHttpClient,
        url: String,
        provisionRequest: ByteArray,
    ): Pair<ByteArray?, List<Attempt>> {
        val standard = Base64.getEncoder().encodeToString(provisionRequest)
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(provisionRequest)

        val variants: List<Pair<String, Pair<String, String>>> = listOf(
            "json-base64url" to (
                "application/json" to JSONObject().put("signed_request", urlSafe).toString()
                ),
            "json-base64" to (
                "application/json" to JSONObject().put("signed_request", standard).toString()
                ),
            "form-base64url" to (
                "application/x-www-form-urlencoded" to "signed_request=$urlSafe"
                ),
            "form-base64" to (
                "application/x-www-form-urlencoded" to
                    "signed_request=" + java.net.URLEncoder.encode(standard, "UTF-8")
                ),
            "raw-protobuf" to (
                "application/octet-stream" to ""
                ),
        )

        val attempts = mutableListOf<Attempt>()

        for ((name, spec) in variants) {
            val (contentType, body) = spec
            val requestBody = if (name == "raw-protobuf") {
                provisionRequest.toRequestBody(contentType.toMediaType())
            } else {
                body.toRequestBody(contentType.toMediaType())
            }

            val request = Request.Builder().url(url).post(requestBody).build()

            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    Triple(response.code, bytes.size, previewOf(bytes))
                }
            }.getOrNull() ?: Triple(-1, 0, "request failed")

            val (code, size, preview) = result
            attempts += Attempt(name, code, size, preview)

            if (code == 200 && size > 0) {
                val body200 = runCatching {
                    client.newCall(request).execute().use { it.body?.bytes() ?: ByteArray(0) }
                }.getOrNull() ?: ByteArray(0)
                return body200 to attempts
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
            text.take(160)
        } else {
            bytes.take(32).joinToString("") { "%02x".format(it) }
        }
    }
}
