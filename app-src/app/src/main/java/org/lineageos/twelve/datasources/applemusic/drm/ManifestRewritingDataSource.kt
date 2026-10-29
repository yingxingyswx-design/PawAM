/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Data source that rewrites Apple's HLS manifest **in place**.
 *
 * The manifest cannot be redirected to a `data:` URI: its segments are relative paths
 * (`mzaf_A123.mp4`), and changing the base URI breaks their resolution — which surfaces as a
 * generic "Source error". Instead the manifest is fetched, rewritten, and served from memory while
 * the caller keeps the original URL, so relative segments still resolve.
 *
 * Non-manifest requests are delegated to [upstream] untouched, so audio segments cost nothing
 * extra.
 */
class ManifestRewritingDataSource(
    private val upstream: DataSource.Factory,
) : DataSource {
    private var delegate: DataSource? = null
    private var buffer: ByteArrayInputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        // Applied to the upstream data source created for each open().
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri

        if (!dataSpec.uri.toString().contains(".m3u8")) {
            val source = upstream.createDataSource()
            delegate = source
            return source.open(dataSpec)
        }

        val source = upstream.createDataSource()
        val length = source.open(dataSpec)
        val raw = try {
            source.readFullyToByteArray()
        } finally {
            source.close()
        }

        val text = raw.toString(Charsets.UTF_8)
        val result = HlsManifestRewriter.rewrite(text)

        if (result.rewritten) {
            val existing = AppleMusicStreamRegistry.licenseFor(dataSpec.uri.toString())
            if (existing != null && result.keyUri != null) {
                AppleMusicStreamRegistry.register(
                    dataSpec.uri.toString(),
                    existing.copy(keyUri = result.keyUri),
                )
            }
            Log.d(LOG_TAG, "Rewrote manifest in place: ${dataSpec.uri}")
        }

        val rewritten = result.manifest.toByteArray(Charsets.UTF_8)
        buffer = ByteArrayInputStream(rewritten)
        bytesRemaining = rewritten.size.toLong()
        return if (length == C.LENGTH_UNSET.toLong()) bytesRemaining else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        delegate?.let { return it.read(buffer, offset, length) }

        val source = this.buffer ?: return C.RESULT_END_OF_INPUT
        val read = source.read(buffer, offset, length)
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        delegate?.close()
        delegate = null
        buffer?.close()
        buffer = null
        uri = null
        bytesRemaining = 0L
    }

    private fun DataSource.readFullyToByteArray(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) {
                break
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        private const val LOG_TAG = "AppleMusicManifest"
    }
}
