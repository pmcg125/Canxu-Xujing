package app.xujing.ui

sealed interface MessageSegment {
    val source: String
    data class Markdown(override val source: String) : MessageSegment
    data class Code(val language: String, override val source: String) : MessageSegment
    data class Html(val document: Boolean, override val source: String) : MessageSegment
}

/** Splits prose, fenced code, and complete HTML blocks without evaluating unfinished fences. */
internal object MessageContentParser {
    private data class Line(val start: Int, val next: Int, val value: String)
    private data class HtmlRange(val end: Int, val document: Boolean)

    private val openingFence = Regex("^ {0,3}(\\x60{3,}|~{3,})(.*)$")
    private val htmlCandidate = Regex(
        "(?i)<(?:!doctype\\s+html\\b|(?:html|head|body|div|style|script|details|span|iframe|p|table|button|section|article|img|svg|pre|ul|ol|li|h[1-6]|form|input|canvas|video|audio|blockquote|hr|br|a|abbr|address|aside|b|caption|code|dd|del|dl|dt|em|figcaption|figure|footer|header|i|ins|kbd|label|main|mark|menu|nav|noscript|optgroup|option|output|q|s|samp|small|source|sub|summary|sup|tbody|td|tfoot|th|thead|time|tr|u|var|wbr)\\b)"
    )
    private val htmlTag = Regex("(?s)<!--.*?-->|<([A-Za-z][A-Za-z0-9:-]*)\\b[^>]*>|</([A-Za-z][A-Za-z0-9:-]*)\\s*>")
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    fun parse(source: String, allowHtml: Boolean = true): List<MessageSegment> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<MessageSegment>()
        var cursor = 0
        var lineStart = 0
        while (lineStart < source.length) {
            val line = lineAt(source, lineStart)
            val opening = openingFence.matchEntire(line.value)
            if (opening == null) {
                lineStart = line.next
                continue
            }

            val marker = opening.groupValues[1]
            val closePattern = Regex("^ {0,3}" + Regex.escape(marker.first().toString()) + "{" + marker.length + ",}\\s*$")
            var close: Line? = null
            var scan = line.next
            while (scan < source.length) {
                val candidate = lineAt(source, scan)
                if (closePattern.matches(candidate.value)) { close = candidate; break }
                scan = candidate.next
            }
            if (close == null) {
                // An unterminated fence is still a code block that runs to the end of the text.
                // Emitting it as Markdown would hide the opening fence and leave stray blank lines.
                appendMixed(result, source.substring(cursor, line.start), allowHtml)
                val language = opening.groupValues[2].trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
                result += MessageSegment.Code(language, source.substring(line.next))
                cursor = source.length
                break
            }

            appendMixed(result, source.substring(cursor, line.start), allowHtml)
            val body = source.substring(line.next, close.start)
            val language = opening.groupValues[2].trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
            val first = body.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val htmlRange = if (language == "html") htmlRange(body, first) else null
            if (allowHtml && language == "html" && htmlRange != null) result += MessageSegment.Html(htmlRange.document, body)
            else result += MessageSegment.Code(language, body)
            cursor = close.next
            lineStart = close.next
        }
        appendMixed(result, source.substring(cursor), allowHtml)
        return result
    }

    private fun appendMixed(result: MutableList<MessageSegment>, source: String, allowHtml: Boolean) {
        if (source.isEmpty()) return
        if (!allowHtml) { result += MessageSegment.Markdown(source); return }
        var cursor = 0
        while (cursor < source.length) {
            val candidate = nextHtmlCandidate(source, cursor) ?: break
            val range = htmlRange(source, candidate)
            if (range == null) { cursor = candidate + 1; continue }
            if (candidate > cursor) result += MessageSegment.Markdown(source.substring(cursor, candidate))
            result += MessageSegment.Html(range.document, source.substring(candidate, range.end))
            cursor = range.end
        }
        if (cursor < source.length) result += MessageSegment.Markdown(source.substring(cursor))
    }

    private fun nextHtmlCandidate(source: String, from: Int): Int? {
        var cursor = from
        var inlineDelimiter = 0
        val tick = 0x60.toChar()
        while (cursor < source.length) {
            if (source[cursor] == tick) {
                var end = cursor + 1
                while (end < source.length && source[end] == tick) end++
                val run = end - cursor
                if (inlineDelimiter == 0) inlineDelimiter = run else if (inlineDelimiter == run) inlineDelimiter = 0
                cursor = end
                continue
            }
            if (inlineDelimiter == 0 && source[cursor] == '<') {
                val match = htmlCandidate.find(source, cursor)
                if (match != null && match.range.first == cursor) return cursor
            }
            cursor++
        }
        return null
    }

    private fun htmlRange(source: String, start: Int): HtmlRange? {
        if (start !in source.indices) return null
        val documentStart = source.regionMatches(start, "<!doctype", 0, 9, ignoreCase = true) ||
            source.regionMatches(start, "<html", 0, 5, ignoreCase = true)
        if (documentStart) {
            val close = Regex("(?i)</html\\s*>").find(source, start) ?: return null
            return HtmlRange(close.range.last + 1, true)
        }
        val opening = htmlTag.find(source, start) ?: return null
        val tag = opening.groupValues[1].lowercase()
        if (tag.isEmpty()) return null
        if (tag == "script" || tag == "style") {
            val close = Regex("(?i)</" + Regex.escape(tag) + "\\s*>").find(source, opening.range.last + 1) ?: return null
            return HtmlRange(close.range.last + 1, false)
        }
        if (tag in voidTags || opening.value.endsWith("/>")) return HtmlRange(opening.range.last + 1, false)

        val stack = mutableListOf(tag)
        var scan = opening.range.last + 1
        while (scan < source.length) {
            val token = htmlTag.find(source, scan) ?: return null
            scan = token.range.last + 1
            if (token.value.startsWith("<!--")) continue
            val opened = token.groupValues[1].lowercase()
            val closed = token.groupValues[2].lowercase()
            when {
                opened.isNotEmpty() && opened !in voidTags && !token.value.endsWith("/>") -> stack += opened
                closed.isNotEmpty() -> {
                    val index = stack.indexOfLast { it == closed }
                    if (index >= 0) {
                        while (stack.size > index) stack.removeAt(stack.lastIndex)
                        if (stack.isEmpty()) return HtmlRange(scan, false)
                    }
                }
            }
        }
        return null
    }

    private fun lineAt(source: String, start: Int): Line {
        val newline = source.indexOf('\n', start)
        val next = if (newline < 0) source.length else newline + 1
        val end = if (newline < 0) source.length else if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
        return Line(start, next, source.substring(start, end))
    }
}
