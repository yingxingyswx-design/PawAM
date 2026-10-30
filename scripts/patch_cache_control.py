# Applies an explicit Cache-Control to Apple Music API requests.

import io
import re

PATH = (
    r"C:\codes\projects\PawAM\app-src\app\src\main\java\org\lineageos\twelve"
    r"\datasources\applemusic\AppleMusicClient.kt"
)

with io.open(PATH, "r", encoding="utf-8") as handle:
    text = handle.read()

old = '''        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $developerToken")
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)

        if (userToken != null) {
            builder.header("Music-User-Token", userToken)
        }
'''

new = '''        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $developerToken")
            .header("Origin", AppleMusicHttp.ORIGIN)
            .header("User-Agent", AppleMusicHttp.USER_AGENT)
            // Apple's API sends no cache validators, so OkHttp never stores these responses and
            // every library visit re-fetches the whole listing. Asking for a long max-age is what
            // makes the shared disk cache effective.
            .cacheControl(
                okhttp3.CacheControl.Builder()
                    .maxAge(CACHE_MAX_AGE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            )

        if (userToken != null) {
            builder.header("Music-User-Token", userToken)
        }
'''

if old not in text:
    raise SystemExit("getJson anchor not found")

text = text.replace(old, new, 1)

const = '''/**
 * How long library metadata may be served from disk before it is fetched again.
 *
 * Library content changes when the user edits it, not on a timer, so a long window is acceptable
 * and it is what makes opening the library instant on a weak connection.
 */
private const val CACHE_MAX_AGE_SECONDS = 12L * 60 * 60

'''

marker = "class AppleMusicClient("
index = text.find(marker)
if index < 0:
    raise SystemExit("class marker not found")

if "CACHE_MAX_AGE_SECONDS =" not in text:
    text = text[:index] + const + text[index:]

with io.open(PATH, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

print("applied")
