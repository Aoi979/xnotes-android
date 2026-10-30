package com.xnotes.core.text

/** Pure source commands. Offsets are UTF-16, matching Compose and the Android input connection. */
data class MarkdownEdit(val text: String, val start: Int, val end: Int = start) {
    companion object {
        fun codeBlock(text: String, start: Int, end: Int, language: String = "", seed: String = ""): MarkdownEdit {
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val selected = text.substring(from, to).ifEmpty { seed }
            val longest = Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0
            val fence = "`".repeat(maxOf(3, longest + 1))
            val before = text.substring(0, from)
            val after = text.substring(to)
            val left = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith('\n')) "\n" else "\n\n"
            val right = if (after.startsWith("\n\n")) "" else if (after.startsWith('\n')) "\n" else "\n\n"
            val insert = "$left$fence$language\n$selected\n$fence$right"
            val at = from + left.length + fence.length + language.length + 1
            return MarkdownEdit(text.replaceRange(from, to, insert), at, at + selected.length)
        }

        fun formula(text: String, start: Int, end: Int, display: Boolean): MarkdownEdit {
            if (!display) return wrap(text, start, end, "$")
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val before = text.substring(0, from)
            val after = text.substring(to)
            val left = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith('\n')) "\n" else "\n\n"
            val right = if (after.startsWith("\n\n")) "" else if (after.startsWith('\n')) "\n" else "\n\n"
            val selected = text.substring(from, to)
            val value = "$left$$\n$selected\n$$$right"
            val at = from + left.length + 3
            return MarkdownEdit(text.replaceRange(from, to, value), at, at + selected.length)
        }

        fun wrap(text: String, start: Int, end: Int, left: String, right: String = left): MarkdownEdit {
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val selected = text.substring(from, to)
            if (selected.length >= left.length + right.length && selected.startsWith(left) && selected.endsWith(right)) {
                val unwrapped = selected.substring(left.length, selected.length - right.length)
                return MarkdownEdit(text.replaceRange(from, to, unwrapped), from, from + unwrapped.length)
            }
            if (from >= left.length && to + right.length <= text.length &&
                text.substring(from - left.length, from) == left && text.substring(to, to + right.length) == right) {
                return MarkdownEdit(text.removeRange(to, to + right.length).removeRange(from - left.length, from),
                    from - left.length, to - left.length)
            }
            return MarkdownEdit(text.replaceRange(from, to, left + selected + right), from + left.length, to + left.length)
        }

        fun lines(text: String, start: Int, end: Int, prefix: String): MarkdownEdit {
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val lineStart = text.lastIndexOf('\n', (from - 1)).let { it + 1 }
            val last = if (to > from && text.getOrNull(to - 1) == '\n') to - 1 else to
            val lineEnd = text.indexOf('\n', last).let { if (it < 0) text.length else it }
            val original = text.substring(lineStart, lineEnd).split('\n')
            val remove = prefix.isNotEmpty() && original.all { it.startsWith(prefix) }
            val changed = original.joinToString("\n") { if (remove) it.removePrefix(prefix) else prefix + it }
            return MarkdownEdit(text.replaceRange(lineStart, lineEnd, changed), lineStart, lineStart + changed.length)
        }

        /** A collapsed Enter only; composing input and code fences are left to the IME. */
        fun enter(text: String, caret: Int): MarkdownEdit? {
            val at = caret.coerceIn(0, text.length)
            val before = text.substring(0, at)
            var fence: String? = null
            for (line in before.lineSequence()) {
                val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1) ?: continue
                if (fence == null) fence = marker else if (marker.first() == fence.first() && marker.length >= fence.length) fence = null
            }
            if (fence != null) return null
            val begin = before.lastIndexOf('\n') + 1
            val line = before.substring(begin)
            val match = Regex("^(\\s*)(?:(> )|([-+*] )(\\[[ xX]\\] )?|(\\d+)([.)] ))(.*)$").matchEntire(line) ?: return null
            val indent = match.groupValues[1]
            val body = match.groupValues[7]
            if (body.isBlank()) return MarkdownEdit(text.removeRange(begin, at), begin)
            val marker = when {
                match.groupValues[2].isNotEmpty() -> "> "
                match.groupValues[3].isNotEmpty() -> match.groupValues[3] + if (match.groupValues[4].isNotEmpty()) "[ ] " else ""
                else -> {
                    val number = match.groupValues[5].toLongOrNull()?.takeIf { it < Long.MAX_VALUE } ?: return null
                    "${number + 1}${match.groupValues[6]}"
                }
            }
            val insert = "\n$indent$marker"
            return MarkdownEdit(text.substring(0, at) + insert + text.substring(at), at + insert.length)
        }

        fun indent(text: String, start: Int, end: Int, outdent: Boolean): MarkdownEdit {
            val from = minOf(start, end).coerceIn(0, text.length)
            val to = maxOf(start, end).coerceIn(from, text.length)
            val begin = text.lastIndexOf('\n', from - 1) + 1
            val last = if (to > from && text.getOrNull(to - 1) == '\n') to - 1 else to
            val changes = ArrayList<Pair<Int, Int>>()
            var line = begin
            while (line <= last) {
                val remove = if (!outdent) 0 else if (text.getOrNull(line) == '\t') 1
                    else text.substring(line).take(4).takeWhile { it == ' ' }.length
                changes += line to remove
                val next = text.indexOf('\n', line)
                if (next < 0) break
                line = next + 1
            }
            val result = StringBuilder(text)
            for ((at, remove) in changes.asReversed()) result.replace(at, at + remove, if (outdent) "" else "    ")
            fun mapped(offset: Int): Int {
                var shift = 0
                for ((at, remove) in changes) {
                    if (at > offset) break
                    shift += if (!outdent) 4 else -minOf(remove, offset - at)
                }
                return offset + shift
            }
            return MarkdownEdit(result.toString(), mapped(start), mapped(end))
        }

        fun matches(text: String, query: String, matchCase: Boolean): List<IntRange> {
            if (query.isEmpty()) return emptyList()
            val out = ArrayList<IntRange>()
            var at = 0
            while (at <= text.length - query.length) {
                val found = text.indexOf(query, at, ignoreCase = !matchCase)
                if (found < 0) break
                out += found until found + query.length
                at = found + query.length
            }
            return out
        }

        /** Build once rather than copying the whole document for every match. Reject an oversized
         * expansion before allocating it, so replacing a common character cannot exhaust the heap. */
        fun replaceAll(text: String, ranges: List<IntRange>, replacement: String, caret: Int): MarkdownEdit? {
            if (ranges.isEmpty()) return null
            val size = ranges.fold(text.length.toLong()) { length, r -> length + replacement.length - r.count() }
            if (size > MarkdownSource.MAX_EDIT_BYTES || size < 0) return null
            val value = buildString(size.toInt()) {
                var at = 0
                for (range in ranges) {
                    append(text, at, range.first)
                    append(replacement)
                    at = range.last + 1
                }
                append(text, at, text.length)
            }
            if (!MarkdownSource.editableText(value)) return null
            return MarkdownEdit(value, caret.coerceIn(0, value.length))
        }
    }
}
