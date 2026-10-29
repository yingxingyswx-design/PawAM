/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {
    // ---------------------------------------------------------------------
    // Time parsing
    // ---------------------------------------------------------------------

    @Test
    fun `parses clock times with hours minutes seconds and fraction`() {
        assertEquals(1234L, TtmlParser.parseTime("00:00:01.234"))
        assertEquals(3723456L, TtmlParser.parseTime("01:02:03.456"))
    }

    @Test
    fun `parses minutes seconds form`() {
        assertEquals(62345L, TtmlParser.parseTime("01:02.345"))
    }

    @Test
    fun `parses bare seconds`() {
        assertEquals(12340L, TtmlParser.parseTime("12.34"))
    }

    @Test
    fun `parses offset time suffixes`() {
        assertEquals(1234L, TtmlParser.parseTime("1234ms"))
        assertEquals(1200L, TtmlParser.parseTime("1.2s"))
    }

    @Test
    fun `rejects blank and malformed values`() {
        assertNull(TtmlParser.parseTime(""))
        assertNull(TtmlParser.parseTime("   "))
        assertNull(TtmlParser.parseTime("abc"))
        assertNull(TtmlParser.parseTime("1:2:3:4"))
    }

    // ---------------------------------------------------------------------
    // Document parsing
    // ---------------------------------------------------------------------

    @Test
    fun `parses word level timing inside a line`() {
        val lyrics = TtmlParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:10.000" end="00:00:12.000">
                  <span begin="00:00:10.000" end="00:00:10.500">Hel</span>
                  <span begin="00:00:10.500" end="00:00:11.000">lo</span>
                  <span begin="00:00:11.000" end="00:00:12.000"> world</span>
                </p>
              </div></body>
            </tt>
            """.trimIndent()
        )

        assertEquals(1, lyrics.lines.size)

        val line = lyrics.lines.first()
        assertEquals("Hello world", line.text)
        assertTrue(line.hasWords)

        val words = line.words!!
        assertEquals(3, words.size)
        assertEquals("Hel", words[0].text)
        assertEquals(10_000L..10_500L, words[0].durationMs)
        assertEquals(10_500L..11_000L, words[1].durationMs)
        assertEquals(" world", words[2].text)
        assertEquals(11_000L..12_000L, words[2].durationMs)
    }

    @Test
    fun `keeps line level text when no spans carry timing`() {
        val lyrics = TtmlParser.parse(
            """
            <tt><body><div>
              <p begin="00:00:05.000" end="00:00:08.000">Just a plain line</p>
            </div></body></tt>
            """.trimIndent()
        )

        assertEquals(1, lyrics.lines.size)

        val line = lyrics.lines.first()
        assertEquals("Just a plain line", line.text)
        assertEquals(5000L..8000L, line.durationMs)
        assertEquals(false, line.hasWords)
    }

    @Test
    fun `sorts lines by start time`() {
        val lyrics = TtmlParser.parse(
            """
            <tt><body><div>
              <p begin="00:00:20.000" end="00:00:22.000">Second</p>
              <p begin="00:00:10.000" end="00:00:12.000">First</p>
            </div></body></tt>
            """.trimIndent()
        )

        assertEquals(listOf("First", "Second"), lyrics.lines.map { it.text })
    }

    @Test
    fun `skips empty lines`() {
        val lyrics = TtmlParser.parse(
            """
            <tt><body><div>
              <p begin="00:00:01.000" end="00:00:02.000">   </p>
              <p begin="00:00:03.000" end="00:00:04.000">Real</p>
            </div></body></tt>
            """.trimIndent()
        )

        assertEquals(1, lyrics.lines.size)
        assertEquals("Real", lyrics.lines.first().text)
    }

    @Test
    fun `returns empty lyrics for malformed documents`() {
        assertEquals(0, TtmlParser.parse("not xml at all").lines.size)
        assertEquals(0, TtmlParser.parse("").lines.size)
        assertEquals(0, TtmlParser.parse("<tt><body>unclosed").lines.size)
    }

    @Test
    fun `words concatenate back to the line text`() {
        val lyrics = TtmlParser.parse(
            """
            <tt><body><div>
              <p begin="0:00:01.000" end="0:00:03.000">
                <span begin="0:00:01.000" end="0:00:01.500">雨</span>
                <span begin="0:00:01.500" end="0:00:02.000">后</span>
                <span begin="0:00:02.000" end="0:00:03.000">的</span>
              </p>
            </div></body></tt>
            """.trimIndent()
        )

        val line = lyrics.lines.first()
        assertEquals("雨后的", line.text)
        assertEquals(line.text, line.words!!.joinToString("") { it.text })
    }
}
