/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

/**
 * Rewrites Apple's web-player HLS manifests into something Media3 can hand to Widevine.
 *
 * Apple declares full-track encryption as:
 *
 * ```
 * #EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,<16-byte key id>"
 * ```
 *
 * Two things are wrong for a native player:
 *
 * 1. `ISO-23001-7` must become `SAMPLE-AES-CENC` together with a Widevine `KEYFORMAT`, otherwise
 *    Media3 does not route the stream through a DRM session at all.
 * 2. The URI holds only the key id. The licence server wants a `pssh` box, so the URI is replaced
 *    with one built from that key id.
 *
 * The **original** URI is returned alongside the rewritten manifest: Apple's licence request must
 * echo it back verbatim, so it cannot be discarded.
 *
 * Everything else in the manifest is passed through untouched.
 */
object HlsManifestRewriter {
    private const val APPLE_METHOD = "METHOD=ISO-23001-7"

    /**
     * Outcome of a rewrite.
     *
     * @param manifest The manifest to feed to Media3 (unchanged when [rewritten] is false).
     * @param keyUri The original `data:` URI from Apple's KEY line, to echo in the licence request.
     * @param rewritten Whether anything was changed.
     */
    data class Result(
        val manifest: String,
        val keyUri: String?,
        val rewritten: Boolean,
    )

    /**
     * Rewrite [manifest].
     *
     * Non-encrypted manifests (preview streams) are returned unchanged with [Result.rewritten]
     * false, which lets the caller play them without a DRM session.
     */
    fun rewrite(manifest: String): Result {
        val normalized = manifest.replace("\r\n", "\n")
        if (!normalized.contains(APPLE_METHOD)) {
            return Result(normalized, keyUri = null, rewritten = false)
        }

        var originalKeyUri: String? = null
        val output = StringBuilder(normalized.length + 512)

        for (line in normalized.split('\n')) {
            if (line.startsWith("#EXT-X-KEY") && line.contains(APPLE_METHOD)) {
                val uri = extractUri(line)
                if (originalKeyUri == null) {
                    originalKeyUri = uri
                }

                output.append(buildKeyLine(uri))
            } else {
                output.append(line)
            }
            output.append('\n')
        }

        return Result(
            manifest = output.toString(),
            keyUri = originalKeyUri,
            rewritten = true,
        )
    }

    /**
     * Build the replacement KEY line.
     *
     * When the original URI yields a usable key id it is replaced by a Widevine pssh box;
     * otherwise the URI is kept so the manifest stays syntactically valid (Media3 will report a
     * clear error instead of a silently broken stream).
     */
    internal fun buildKeyLine(originalUri: String?): String {
        val keyId = originalUri?.let { WidevinePssh.keyIdFromDataUri(it) }

        val uriAttribute = if (keyId != null) {
            WidevinePssh.boxToDataUri(WidevinePssh.buildBox(keyId))
        } else {
            originalUri.orEmpty()
        }

        return "#EXT-X-KEY:METHOD=SAMPLE-AES-CENC," +
            "URI=\"$uriAttribute\"," +
            "KEYFORMAT=\"${WidevinePssh.WIDEVINE_KEYFORMAT}\"," +
            "KEYFORMATVERSIONS=\"1\""
    }

    /**
     * Extract the `URI` attribute value from a KEY line.
     */
    internal fun extractUri(line: String): String? {
        val marker = "URI=\""
        val start = line.indexOf(marker)
        if (start < 0) {
            return null
        }

        val valueStart = start + marker.length
        val end = line.indexOf('"', valueStart)
        if (end < 0) {
            return null
        }

        return line.substring(valueStart, end)
    }
}
