/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import org.json.JSONObject

/**
 * Builds the licence request body Apple's `acquireWebPlaybackLicense` accepts.
 *
 * The shape below was verified against the live endpoint: omitting `key-system` yields HTTP 500,
 * and a syntactically valid body with an unusable challenge returns
 * `{"errorCode":-42585,"status":-1021}` — i.e. the request was understood and rejected on content,
 * not on format.
 */
object WidevineLicenseRequest {
    /** License request body for [challenge]. */
    fun body(
        challenge: ByteArray,
        keyUri: String,
        adamId: String,
        isLibrary: Boolean = false,
    ): String = JSONObject()
        .put("challenge", java.util.Base64.getEncoder().encodeToString(challenge))
        .put("key-system", KEY_SYSTEM)
        // Apple requires the original KEY URI from the manifest, echoed verbatim.
        .put("uri", keyUri)
        .put("adamId", adamId)
        .put("isLibrary", isLibrary)
        .put("user-initiated", true)
        .toString()

    /**
     * Extract the licence bytes from Apple's response.
     *
     * Apple answers either `{"license":"<base64>"}` or `{"license":...}` nested under a stream
     * entry, and reports failures as a non-zero `errorCode`. Returns null for any failure so the
     * caller can surface a clear "not entitled" state instead of a playback error.
     */
    fun parseResponse(responseBody: String): ByteArray? {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null

        val errorCode = root.optInt("errorCode", 0)
        if (errorCode != 0) {
            return null
        }

        val license = root.optString("license").takeIf { it.isNotBlank() }
            ?: root.optJSONArray("streams")
                ?.optJSONObject(0)
                ?.optString("license")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            java.util.Base64.getDecoder().decode(license)
        }.getOrNull()
    }

    const val KEY_SYSTEM = "com.widevine.alpha"
}
