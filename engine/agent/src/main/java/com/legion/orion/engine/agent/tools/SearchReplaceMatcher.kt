package com.legion.orion.engine.agent.tools

/**
 * Whitespace-tolerant block matcher for search_replace, mirroring the desktop semantics.
 * Tries an exact substring match first; if that fails, matches line-by-line ignoring each line's
 * leading/trailing whitespace and blank-line differences, so indentation drift doesn't break edits.
 */
object SearchReplaceMatcher {

    data class Match(val start: Int, val end: Int) // char offsets in the original text

    /** Returns the first match of [search] in [text], or null. */
    fun find(text: String, search: String): Match? {
        if (search.isEmpty()) return null
        // 1) exact, but only when line-aligned — a mid-indentation substring hit would corrupt
        //    indentation and defeat whitespace tolerance.
        val exact = text.indexOf(search)
        if (exact >= 0 && (exact == 0 || text[exact - 1] == '\n')) {
            return Match(exact, exact + search.length)
        }
        // 2) line-based, whitespace-insensitive
        return findByLines(text, search)
    }

    /** Replaces the first match of [search] with [replacement]; null if no match. */
    fun replaceFirst(text: String, search: String, replacement: String): String? {
        val m = find(text, search) ?: return null
        return text.substring(0, m.start) + replacement + text.substring(m.end)
    }

    private fun findByLines(text: String, search: String): Match? {
        val textLines = splitKeepingOffsets(text)
        val searchLines = search.split("\n").map { it.trim() }.dropLastWhile { it.isEmpty() }
            .dropWhile { it.isEmpty() }
        if (searchLines.isEmpty()) return null

        var i = 0
        while (i < textLines.size) {
            if (linesMatchAt(textLines, i, searchLines)) {
                val startOffset = textLines[i].start
                val endLineIdx = i + searchLines.size - 1
                val endOffset = textLines[endLineIdx].end
                return Match(startOffset, endOffset)
            }
            i++
        }
        return null
    }

    private fun linesMatchAt(textLines: List<LineSpan>, startIdx: Int, searchLines: List<String>): Boolean {
        if (startIdx + searchLines.size > textLines.size) return false
        for (k in searchLines.indices) {
            if (textLines[startIdx + k].text.trim() != searchLines[k]) return false
        }
        return true
    }

    private data class LineSpan(val text: String, val start: Int, val end: Int)

    /** Splits text into lines, tracking char offsets (end excludes the trailing newline). */
    private fun splitKeepingOffsets(text: String): List<LineSpan> {
        val spans = ArrayList<LineSpan>()
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                spans.add(LineSpan(text.substring(lineStart, i), lineStart, i))
                lineStart = i + 1
            }
            i++
        }
        spans.add(LineSpan(text.substring(lineStart), lineStart, text.length))
        return spans
    }
}
