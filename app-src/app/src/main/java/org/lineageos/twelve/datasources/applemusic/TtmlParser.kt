/*
 * SPDX-FileCopyrightText: 2026 The PawAM Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.applemusic

import org.lineageos.twelve.models.Lyrics
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for Apple's TTML lyric documents (the `syllable-lyrics` payload).
 *
 * Apple ships word-level timing as TTML: one `<p>` per line, and `<span begin end>` elements
 * inside it whose text is one syllable or word. Only the **text content and timing** are read;
 * styling, translations and background-vocal roles are ignored for now, because Twelve's lyrics
 * view renders a single column of text.
 *
 * Two deliberate decisions:
 *
 * - **Timing is read from attributes, never inferred across elements.** Nested spans are common;
 *   walking them in document order and using each span's own `begin`/`end` keeps the timeline
 *   monotonic without needing to guess.
 * - **A line with no timed spans is still kept.** Apple sometimes emits plain text inside `<p>`
 *   with only line-level timing; dropping it would lose whole verses.
 *
 * Malformed input yields an empty [Lyrics] instead of throwing: a broken lyric document must not
 * take down the player.
 */
object TtmlParser {
    /**
     * Parse [ttml] into [Lyrics].
     *
     * @param ttml The raw TTML document.
     * @return Parsed lyrics. Empty when nothing usable was found.
     */
    fun parse(ttml: String): Lyrics {
        val document = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = false
                    // Never resolve external entities: the document comes from the network.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(ttml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return Lyrics(emptyList())

        val lines = mutableListOf<Lyrics.Line>()

        for (paragraph in document.getElementsByTagName("p").asSequence()) {
            val text = buildLine(paragraph) ?: continue
            lines += text
        }

        return Lyrics(lines.sortedBy { it.durationMs?.first ?: Long.MAX_VALUE })
    }

    /**
     * Build one [Lyrics.Line] from a `<p>` element, or null when it carries no text.
     */
    private fun buildLine(paragraph: Element): Lyrics.Line? {
        val spans = paragraph.getElementsByTagName("span").asSequence().toList()

        val words = spans.mapNotNull { span ->
            val text = span.textContent.orEmpty()
            // Preserve spaces: they carry the line's spacing, and trimming would merge words.
            if (text.isBlank()) {
                return@mapNotNull null
            }

            val begin = parseTime(span.getAttribute("begin"))
            val end = parseTime(span.getAttribute("end"))

            // A span without timing cannot be rendered karaoke-style; skip it so the line falls
            // back to line-level highlighting instead of showing a zero-length word.
            if (begin == null) {
                return@mapNotNull null
            }

            Lyrics.Word(
                text = text,
                durationMs = begin..(end ?: begin),
            )
        }

        val lineText = if (words.isNotEmpty()) {
            words.joinToString(separator = "") { it.text }
        } else {
            paragraph.textContent.orEmpty()
        }

        if (lineText.isBlank()) {
            return null
        }

        val begin = parseTime(paragraph.getAttribute("begin"))
            ?: words.firstOrNull()?.durationMs?.first
        val end = parseTime(paragraph.getAttribute("end"))
            ?: words.lastOrNull()?.durationMs?.last

        return Lyrics.Line(
            text = lineText.trim(),
            durationMs = begin?.let { start -> start..(end ?: start) },
            words = words.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Parse a TTML time expression into milliseconds.
     *
     * Accepts the forms Apple emits: `hh:mm:ss.mmm`, `mm:ss.mmm`, `ss.mmm`, `123ms` and `1.2s`.
     * Returns null for anything else (including an empty attribute).
     */
    internal fun parseTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) {
            return null
        }

        // Offset-time forms (`123ms`, `1.2s`) never contain a colon, so try them first.
        toOffsetMillis(value)?.let { return it }

        val parts = value.split(':')

        val seconds = when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return null
                val minutes = parts[1].toDoubleOrNull() ?: return null
                val secs = parts[2].toDoubleOrNull() ?: return null
                hours * 3600.0 + minutes * 60.0 + secs
            }

            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return null
                val secs = parts[1].toDoubleOrNull() ?: return null
                minutes * 60.0 + secs
            }

            1 -> parts[0].toDoubleOrNull() ?: return null

            else -> return null
        }

        return (seconds * 1000.0).toLong().takeIf { it >= 0 }
    }

    /**
     * Parse a clock-less offset time (`123ms`, `1.2s`) into milliseconds.
     */
    private fun toOffsetMillis(value: String): Long? = when {
        value.endsWith("ms", ignoreCase = true) ->
            value.dropLast(2).trim().toDoubleOrNull()?.toLong()?.takeIf { it >= 0 }

        value.endsWith("s", ignoreCase = true) ->
            value.dropLast(1).trim().toDoubleOrNull()?.times(1000.0)?.toLong()
                ?.takeIf { it >= 0 }

        else -> null
    }
}

/**
 * Read a live [NodeList] as a [Sequence] in document order.
 */
private fun org.w3c.dom.NodeList.asSequence(): Sequence<Element> = sequence {
    for (index in 0 until length) {
        val node = item(index)
        if (node.nodeType == Node.ELEMENT_NODE) {
            yield(node as Element)
        }
    }
}
