/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic.drm

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a stream URL to the licence information needed to decrypt it.
 *
 * The licence URL and the manifest's key URI are both produced by the playback lease, which is
 * fetched per track. Media3 asks for DRM initialisation and for the licence synchronously, so the
 * values have to be published somewhere keyed by something Media3 knows — the stream URL is that
 * key.
 *
 * Entries are dropped once a track has been resolved and played, so the map cannot grow without
 * bound during a long session.
 */
object AppleMusicStreamRegistry {
    /**
     * Licence context for one stream.
     *
     * @param licenseUrl Apple's `hls-key-server-url`.
     * @param keyUri The original `#EXT-X-KEY` data URI, echoed verbatim in the licence request.
     * @param adamId Catalog song id.
     * @param developerToken Developer token valid at resolution time.
     * @param musicUserToken User token valid at resolution time.
     * @param certUrl Apple's Widevine provisioning endpoint, when offered.
     */
    data class License(
        val licenseUrl: String,
        val keyUri: String,
        val adamId: String,
        val developerToken: String,
        val musicUserToken: String,
        val certUrl: String = "",
    )

    private val licenses = ConcurrentHashMap<String, License>()

    fun register(streamUrl: String, license: License) {
        licenses[streamUrl] = license
    }

    fun licenseFor(streamUrl: String): License? = licenses[streamUrl]

    fun forget(streamUrl: String) {
        licenses.remove(streamUrl)
    }
}
