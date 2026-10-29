/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.lineageos.twelve.datasources.applemusic.AppleMusicHttp
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Media3 plumbing that turns Apple's encrypted HLS into a playable Widevine stream.
 *
 * Two pieces are needed and both key off the stream URL:
 *
 * 1. **Manifest rewriting.** When Media3 loads an `.m3u8` that belongs to Apple, the body is
 *    rewritten from `ISO-23001-7` to `SAMPLE-AES-CENC` with a Widevine `pssh` URI. Without this
 *    Media3 never opens a DRM session and plays silence.
 * 2. **Licence exchange.** The Widevine challenge is POSTed to Apple's licence endpoint together
 *    with the original KEY URI, and the returned licence is handed back to Media3.
 */
object AppleMusicDrm {
    private const val LOG_TAG = "AppleMusicDrm"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Data source factory that rewrites Apple manifests on the fly.
     *
     * Only manifest responses are inspected; segment requests pass straight through, which keeps
     * the overhead to one small read per playback.
     */
    /**
     * Data source factory that rewrites Apple manifests in place.
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { ManifestRewritingDataSource(upstream) }
    /**
     * Callback that performs the Widevine licence exchange with Apple.
     */
    class LicenseCallback(
        private val license: AppleMusicStreamRegistry.License,
    ) : androidx.media3.exoplayer.drm.MediaDrmCallback {

        override fun executeProvisionRequest(
            uuid: UUID,
            request: androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest,
        ): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
            // Use Google's own provisioning service: it replies in exactly the format the CDM
            // parses. Apple's `widevine-cert-url` remains the fallback, but its protobuf answer has
            // not matched what the CDM expects (see docs/HANDOVER.md).
            val provisionUrl = request.defaultUrl.ifBlank { license.certUrl }

            // Google's provisioning endpoint expects the OMA form field `signed_request` holding
            // the base64 of the provision request. Posting the raw protobuf yields HTTP 400.
            val formBody = "signed_request=" + java.net.URLEncoder.encode(
                java.util.Base64.getEncoder().encodeToString(request.data),
                "UTF-8",
            )

            val httpRequest = Request.Builder()
                .url(provisionUrl)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("Authorization", "Bearer ${license.developerToken}")
                .header("Media-User-Token", license.musicUserToken)
                .header("Origin", AppleMusicHttp.ORIGIN)
                .header("User-Agent", AppleMusicHttp.USER_AGENT)
                .build()

            Log.d(LOG_TAG, "Provisioning via $provisionUrl")

            val (code, payload) = runCatching {
                httpClient.newCall(httpRequest).execute().use { it.code to it.body?.bytes() }
            }.getOrNull() ?: throw IllegalStateException("Provisioning request failed")

            Log.d(LOG_TAG, "Provisioning HTTP $code (${payload?.size ?: 0} bytes)")

            if (code != 200 || payload == null || payload.isEmpty()) {
                throw IllegalStateException("Provisioning rejected: HTTP $code")
            }

            // The CDM parses the provisioning reply as the legacy OMA envelope
            // `{"signed_response":"<base64>"}`, but Apple answers with a protobuf. Wrapping the
            // whole protobuf fails with `Cannot locate start substring`, so the inner
            // `signed_response` field has to be unwrapped first.
            val body = ProvisioningResponse.toJsonEnvelope(payload)
            Log.d(LOG_TAG, "Provisioning envelope size=${body.length}")

            return androidx.media3.exoplayer.drm.MediaDrmCallback.Response(
                body.toByteArray(Charsets.UTF_8)
            )
        }

        override fun executeKeyRequest(
            uuid: UUID,
            request: androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest,
        ): androidx.media3.exoplayer.drm.MediaDrmCallback.Response {
            val token = license.developerToken
            if (token.isEmpty()) {
                throw IllegalStateException("Missing developer token")
            }

            val body = WidevineLicenseRequest.body(
                challenge = request.data,
                keyUri = license.keyUri,
                adamId = license.adamId,
            )

            val httpRequest = Request.Builder()
                .url(license.licenseUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("Media-User-Token", license.musicUserToken)
                .header("Origin", AppleMusicHttp.ORIGIN)
                .header("User-Agent", AppleMusicHttp.USER_AGENT)
                .build()

            val response = runCatching {
                httpClient.newCall(httpRequest).execute().use { it.code to it.body?.string() }
            }.getOrNull() ?: throw IllegalStateException("Licence request failed")

            val (code, payload) = response
            Log.d(LOG_TAG, "Licence HTTP $code")

            if (code != 200 || payload == null) {
                throw IllegalStateException("Licence rejected: HTTP $code")
            }

            val licenseBytes = WidevineLicenseRequest.parseResponse(payload)
                ?: throw IllegalStateException("Licence response carried no licence")

            return androidx.media3.exoplayer.drm.MediaDrmCallback.Response(licenseBytes)
        }
    }

    /**
     * DRM session manager provider backed by the stream registry.
     *
     * Media3 asks for a manager per media item; when the item has no registered licence it is an
     * unencrypted preview and no DRM is required, so null is returned.
     */
    fun drmSessionManagerProvider(): androidx.media3.exoplayer.drm.DrmSessionManagerProvider =
        androidx.media3.exoplayer.drm.DrmSessionManagerProvider { mediaItem ->
            val url = mediaItem.localConfiguration?.uri?.toString()
            val license = url?.let { AppleMusicStreamRegistry.licenseFor(it) }

            if (license == null) {
                androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED
            } else {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        androidx.media3.common.C.WIDEVINE_UUID,
                        androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER,
                    )
                    .build(LicenseCallback(license))
            }
        }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }
}
