package com.xnotes.core.text

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Node

/** Raw inline Markdown is retained in each cell, rather than round-tripping rendered text. */
data class MarkdownTable(val rows: List<List<String>>, val alignments: List<ParaAlign?>) {
    init { require(rows.isNotEmpty() && alignments.isNotEmpty() && rows.all { it.size == alignments.size }) }
    val columns: Int get() = alignments.size
    fun cell(row: Int, col: Int, value: String) = copy(rows = rows.mapIndexed { r, cells ->
        if (r != row) cells else cells.mapIndexed { c, old -> if (c == col) value.replace('\n', ' ').replace('\r', ' ') else old }
    })
    fun insertRow(at: Int) = copy(rows = rows.toMutableList().apply { add(at.coerceIn(1, size), List(columns) { "" }) })
    fun removeRow(at: Int) = if (at <= 0 || at !in rows.indices) this else copy(rows = rows.filterIndexed { i, _ -> i != at })
    fun insertColumn(at: Int): MarkdownTable {
        val col = at.coerceIn(0, columns)
        return copy(rows = rows.map { it.toMutableList().apply { add(col, "") } },
            alignments = alignments.toMutableList().apply { add(col, null) })
    }
    fun removeColumn(at: Int) = if (columns <= 1 || at !in alignments.indices) this else copy(
        rows = rows.map { it.filterIndexed { i, _ -> i != at } }, alignments = alignments.filterIndexed { i, _ -> i != at })
    fun align(col: Int, alignment: ParaAlign?) = copy(alignments = alignments.mapIndexed { i, old -> if (i == col) alignment else old })
    fun markdown(prefix: String = ""): String {
        fun line(cells: List<String>) = prefix + cells.joinToString(" | ", "| ", " |")
        val separator = line(alignments.map { when (it) {
            ParaAlign.LEFT -> ":---"; ParaAlign.CENTER -> ":---:"; ParaAlign.RIGHT -> "---:"; else -> "---"
        } })
        return (listOf(line(rows.first().map(::escapePipes)), separator) + rows.drop(1).map { line(it.map(::escapePipes)) }).joinToString("\n")
    }

    companion object {
        fun contains(text: String, caret: Int): Boolean {
            val starts = MarkdownBlocks.lineStarts(text)
            fun visit(n: Node): Boolean {
                if (n is TableBlock && n.sourceSpans.isNotEmpty()) {
                    val first = n.sourceSpans.first(); val last = n.sourceSpans.last()
                    if (caret in starts[first.lineIndex]..starts[last.lineIndex] + last.columnIndex + last.length) return true
                }
                var c = n.firstChild
                while (c != null) { if (visit(c)) return true; c = c.next }
                return false
            }
            return visit(MarkdownParser.sourceTree(text))
        }

        fun empty(rows: Int, columns: Int) = MarkdownTable(List(rows) { List(columns) { "" } }, List(columns) { null })
        private fun escapePipes(value: String): String = buildString {
            var slashes = 0
            for (c in value) {
                if (c == '|' && slashes % 2 == 0) append('\\')
                append(if (c == '\n' || c == '\r') ' ' else c)
                slashes = if (c == '\\') slashes + 1 else 0
            }
        }

        /** The parser establishes table boundaries. Splitting raw rows preserves inline syntax. */
        fun at(text: String, caret: Int): Region? {
            val starts = MarkdownBlocks.lineStarts(text)
            var found: Region? = null
            fun visit(node: Node) {
                if (node is TableBlock) {
                    val spans = node.sourceSpans
                    val first = spans.firstOrNull() ?: return
                    val last = spans.last()
                    val begin = starts[first.lineIndex]
                    val end = starts[last.lineIndex] + last.columnIndex + last.length
                    if (caret !in begin..end) return
                    val prefix = text.substring(begin, begin + first.columnIndex)
                    if (!prefix.matches(Regex("[ >]*")) || spans.any { text.substring(starts[it.lineIndex], starts[it.lineIndex] + it.columnIndex) != prefix }) return
                    val raw = spans.map { text.substring(starts[it.lineIndex] + it.columnIndex, starts[it.lineIndex] + it.columnIndex + it.length) }
                    if (raw.size < 2) return
                    val header = cells(raw[0])
                    val separators = cells(raw[1])
                    if (header.isEmpty() || separators.size != header.size) return
                    val rows = (listOf(header) + raw.drop(2).map(::cells))
                    if (rows.any { it.size > header.size }) return // Never silently discard extra source cells.
                    val table = MarkdownTable(rows.map { row -> List(header.size) { row.getOrElse(it) { "" } } },
                        separators.map { when {
                            it.startsWith(':') && it.endsWith(':') -> ParaAlign.CENTER
                            it.startsWith(':') -> ParaAlign.LEFT; it.endsWith(':') -> ParaAlign.RIGHT; else -> null
                        } })
                    found = Region(begin, end, prefix, table, text.substring(begin, end))
                }
                var c = node.firstChild
                while (c != null) { visit(c); c = c.next }
            }
            visit(MarkdownParser.sourceTree(text))
            return found
        }

        private fun cells(line: String): List<String> {
            val s = line.trim()
            val stops = ArrayList<Int>()
            var slashes = 0
            s.forEachIndexed { i, c ->
                if (c == '|' && slashes % 2 == 0) stops += i
                slashes = if (c == '\\') slashes + 1 else 0
            }
            val cuts = listOf(-1) + stops + listOf(s.length)
            val cells = cuts.zipWithNext { a, b -> s.substring(a + 1, b).trim() }.toMutableList()
            if (stops.firstOrNull() == 0) cells.removeAt(0)
            if (stops.lastOrNull() == s.lastIndex) cells.removeAt(cells.lastIndex)
            return cells
        }

        fun insert(text: String, start: Int, end: Int, table: MarkdownTable): MarkdownEdit {
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val before = text.substring(0, from)
            val after = text.substring(to)
            val left = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith('\n')) "\n" else "\n\n"
            val right = if (after.startsWith("\n\n")) "" else if (after.startsWith('\n')) "\n" else "\n\n"
            val value = left + table.markdown() + right
            val at = from + left.length + 2
            return MarkdownEdit(text.replaceRange(from, to, value), at, at + table.rows[0][0].length)
        }
    }

    data class Region(val start: Int, val end: Int, val prefix: String, val table: MarkdownTable, val original: String) {
        fun replace(text: String, replacement: MarkdownTable): MarkdownEdit? {
            if (end > text.length || text.substring(start, end) != original) return null
            val value = replacement.markdown(prefix)
            return MarkdownEdit(text.replaceRange(start, end, value), start + prefix.length + 2)
        }
    }
}
