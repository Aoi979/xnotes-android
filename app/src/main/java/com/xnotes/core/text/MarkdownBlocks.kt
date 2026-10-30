package com.xnotes.core.text

import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.ext.front.matter.YamlFrontMatterBlock

/** Block commands use the same parser as reading, so code, HTML and tables stay literal. */
object MarkdownBlocks {
    private data class Change(val from: Int, val to: Int, val value: String)

    fun isLiteral(text: String, caret: Int): Boolean {
        val starts = lineStarts(text)
        val line = text.lastIndexOf('\n', caret.coerceIn(0, text.length) - 1) + 1
        fun visit(n: Node): Boolean {
            if (n is FencedCodeBlock || n is IndentedCodeBlock || n is HtmlBlock || n is YamlFrontMatterBlock) {
                val lines = n.sourceSpans.map { starts[it.lineIndex] }
                if (lines.isNotEmpty() && line in lines.first()..lines.last()) return true
            }
            var c = n.firstChild
            while (c != null) { if (visit(c)) return true; c = c.next }
            return false
        }
        return visit(MarkdownParser.sourceTree(text))
    }

    fun heading(text: String, start: Int, end: Int, level: Int): MarkdownEdit? {
        require(level in 0..6)
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(from, text.length)
        val firstLine = text.lastIndexOf('\n', from - 1) + 1
        val last = if (to > from && text.getOrNull(to - 1) == '\n') to - 1 else to
        val lastLine = text.lastIndexOf('\n', last - 1) + 1
        val starts = lineStarts(text)
        val changes = ArrayList<Change>()
        val handled = HashSet<Int>()
        var protected = false
        fun visit(node: Node) {
            if (node is FencedCodeBlock || node is IndentedCodeBlock || node is HtmlBlock || node is YamlFrontMatterBlock) {
                val lines = node.sourceSpans.map { starts[it.lineIndex] }
                if (lines.isNotEmpty() && firstLine in lines.first()..lines.last()) protected = true
            }
            if (node is Heading || node is Paragraph) {
                val spans = node.sourceSpans
                val setext = node is Heading && spans.size >= 2 && spans.last().let { span ->
                    val at = starts[span.lineIndex] + span.columnIndex
                    text.substring(at, at + span.length).matches(Regex("[=-]+[ \\t]*"))
                }
                if (setext && spans.size != 2) return // Multi-line setext stays available as source.
                val contentSpans = if (setext) spans.dropLast(1) else spans
                val selected = spans.any { starts[it.lineIndex] in firstLine..lastLine }
                if (selected) for (span in contentSpans) {
                    val line = starts[span.lineIndex]
                    if ((!setext && line !in firstLine..lastLine) || !handled.add(line)) continue
                    val at = line + span.columnIndex
                    val source = text.substring(at, at + span.length)
                    val marker = Regex("^#{1,6}(?:[ \\t]+|$)").find(source)?.value.orEmpty()
                    changes += Change(at, at + marker.length, if (level == 0) "" else "#".repeat(level) + " ")
                    if (node is Heading && level == 0 && marker.isNotEmpty()) {
                        Regex("[ \\t]+#+[ \\t]*$").find(source)?.let { closing ->
                            if (closing.range.first >= marker.length) changes += Change(at + closing.range.first, at + source.length, "")
                        }
                    }
                }
                if (selected && setext) {
                    val underline = starts[spans.last().lineIndex]
                    val stop = text.indexOf('\n', underline).let { if (it < 0) text.length else it + 1 }
                    changes += Change(underline, stop, "")
                }
            }
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(MarkdownParser.sourceTree(text))
        // Empty paragraphs have no AST node. Do not treat blank lines inside code as paragraphs.
        val lineEnd = text.indexOf('\n', firstLine).let { if (it < 0) text.length else it }
        if (from == to && changes.isEmpty() && level > 0 && !protected &&
            text.substring(firstLine, lineEnd).let { it.length <= 3 && it.all { c -> c == ' ' } })
            changes += Change(firstLine, lineEnd, "#".repeat(level) + " ")
        if (changes.isEmpty()) return null
        val ordered = changes.sortedBy { it.from }
        val value = buildString {
            var at = 0
            for (c in ordered) { append(text, at, c.from); append(c.value); at = c.to }
            append(text, at, text.length)
        }
        fun map(offset: Int): Int {
            var shift = 0
            for (c in ordered) {
                if (offset < c.from) break
                if (offset < c.to) return c.from + shift + c.value.length
                shift += c.value.length - (c.to - c.from)
            }
            return (offset + shift).coerceIn(0, value.length)
        }
        return MarkdownEdit(value, map(start.coerceIn(0, text.length)), map(end.coerceIn(0, text.length)))
    }

    fun headingLevel(text: String, caret: Int): Int? {
        val starts = lineStarts(text)
        val line = text.lastIndexOf('\n', caret.coerceIn(0, text.length) - 1) + 1
        fun find(node: Node): Int? {
            if (node.sourceSpans.any { starts[it.lineIndex] == line }) {
                if (node is Heading) return node.level
                if (node is Paragraph) return 0
            }
            var c = node.firstChild
            while (c != null) { find(c)?.let { return it }; c = c.next }
            return null
        }
        return if (text.isEmpty()) 0 else find(MarkdownParser.sourceTree(text)) ?: run {
            val stop = text.indexOf('\n', line).let { if (it < 0) text.length else it }
            if (text.substring(line, stop).isBlank() && !isLiteral(text, caret)) 0 else null
        }
    }

    internal fun lineStarts(text: String): List<Int> = buildList {
        add(0); text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }
}
