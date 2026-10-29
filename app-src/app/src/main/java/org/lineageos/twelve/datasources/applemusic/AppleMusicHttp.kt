/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP plumbing for every Apple Music call.
 *
 * The `Origin` header is not decorative: Apple binds the web player's developer token to
 * `origin = https://music.apple.com`, and a request carrying that token *without* the matching
 * `Origin` is rejected with 401. Keeping it in one place prevents a future call site from
 * forgetting it.
 */
object AppleMusicHttp {
    const val API_HOST = "https://api.music.apple.com"
    const val ORIGIN = "https://music.apple.com"

    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Artwork URLs returned by the API contain `{w}`/`{h}` placeholders to be substituted. */
    const val ARTWORK_SIZE = 632

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Scraping the developer token downloads the web player bundle (~3.3 MB), which needs
            // far more than a normal API round trip on a mobile connection.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
