/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests against a **real** Apple `syllable-lyrics` payload.
 *
 * The excerpt below is copied verbatim from a live response, including the parts that are easy to
 * get wrong: bare-seconds timestamps (`11.282`), the `itunes:`/`ttm:` namespace prefixes, and
 * multi-character spans whose text must keep its position inside the line.
 */
class TtmlParserAppleFormatTest {
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body>
            <div>
              <p begin="11.282" end="13.541" itunes:key="L1" ttm:agent="v1"><span begin="11.282" end="11.839">如</span><span begin="11.839" end="12.159">果沒</span><span begin="12.159" end="12.797">有如</span><span begin="12.797" end="13.541">果</span></p>
              <p begin="14.000" end="16.500" itunes:key="L2" ttm:agent="v1"><span begin="14.000" end="15.000">Second</span><span begin="15.000" end="16.500"> line</span></p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun `parses real apple ttml into word-timed lines`() {
        val lyrics = TtmlParser.parse(ttml)

        assertEquals(2, lyrics.lines.size)

        val first = lyrics.lines[0]
        assertEquals(11282L..13541L, first.durationMs)
        assertEquals(4, first.words!!.size)
        assertEquals("如", first.words!![0].text)
        assertEquals(11282L..11839L, first.words!![0].durationMs)
        // A span may cover several characters; it is one timing unit.
        assertEquals("果沒", first.words!![1].text)
    }

    @Test
    fun `concatenated words reproduce the line text`() {
        val lyrics = TtmlParser.parse(ttml)

        for (line in lyrics.lines) {
            assertTrue("line must carry words: ${line.text}", line.hasWords)
            assertEquals(line.text, line.words!!.joinToString("") { it.text }.trim())
        }
    }

    @Test
    fun `leading space in a span is preserved for layout`() {
        val lyrics = TtmlParser.parse(ttml)

        val second = lyrics.lines[1]
        // " line" keeps its leading space so the rendered text does not collapse words together.
        assertEquals(" line", second.words!![1].text)
        assertEquals("Second line", second.text)
    }
}
