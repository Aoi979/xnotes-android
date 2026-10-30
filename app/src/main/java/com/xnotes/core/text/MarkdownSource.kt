package com.xnotes.core.text

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Normalized editor text, with the original newline spelling and BOM kept outside the field. */
data class MarkdownSource(
    val text: String,
    val endings: List<String> = List(text.count { it == '\n' }) { "\n" },
    val bom: Boolean = false,
) {
    init { require(endings.size == text.count { it == '\n' }) }

    /** Bounded until the source surface can virtualize paragraphs. A byte-only limit is insufficient
     * for documents containing thousands of short lines. Decoding/recovery retain the larger bound. */
    val editable: Boolean get() = editableText(text)

    /** Preserve newlines outside the changed range, including in mixed-LF/CRLF documents. */
    fun edited(value: String): MarkdownSource {
        val next = value.replace("\r\n", "\n").replace('\r', '\n')
        if (next == text) return this
        var prefix = 0
        while (prefix < minOf(text.length, next.length) && text[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length, next.length) - prefix &&
            text[text.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val before = text.take(prefix).count { it == '\n' }
        val after = text.takeLast(suffix).count { it == '\n' }
        val inserted = next.substring(prefix, next.length - suffix).count { it == '\n' }
        val usual = endings.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "\n"
        return MarkdownSource(next, endings.take(before) + List(inserted) { usual } + endings.takeLast(after), bom)
    }

    fun bytes(): ByteArray = buildString(text.length + endings.size + 1) {
        if (bom) append('\uFEFF')
        var line = 0
        for (c in text) if (c == '\n') append(endings[line++]) else append(c)
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_EDIT_BYTES = 256 * 1024
        const val MAX_EDIT_LINES = 2_000

        fun editableText(text: String): Boolean = text.length <= MAX_EDIT_BYTES &&
            text.count { it == '\n' } < MAX_EDIT_LINES &&
            (text.length <= MAX_EDIT_BYTES / 4 || text.toByteArray(Charsets.UTF_8).size <= MAX_EDIT_BYTES)

        fun decode(bytes: ByteArray): MarkdownSource {
            require(bytes.size <= MAX_BYTES) { "Markdown file exceeds 8 MiB" }
            val raw = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val bom = raw.startsWith('\uFEFF')
            val content = if (bom) raw.substring(1) else raw
            val endings = ArrayList<String>()
            val text = buildString(content.length) {
                var i = 0
                while (i < content.length) {
                    val c = content[i++]
                    if (c == '\r') {
                        val crlf = i < content.length && content[i] == '\n'
                        if (crlf) i++
                        endings += if (crlf) "\r\n" else "\r"
                        append('\n')
                    } else {
                        if (c == '\n') endings += "\n"
                        append(c)
                    }
                }
            }
            return MarkdownSource(text, endings, bom)
        }

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
