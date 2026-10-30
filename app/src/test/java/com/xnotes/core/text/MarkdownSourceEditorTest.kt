package com.xnotes.core.text

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.ExportZip
import org.junit.Assert.*
import org.junit.Test

class MarkdownSourceEditorTest {
    @Test fun `open and encode preserve BOM mixed newlines front matter and trailing spaces`() {
        val raw = "\uFEFF---\r\ntitle: 测试\n---\r\n\r\n[ref]: relative.md  \n<!-- comment -->\r"
        assertArrayEquals(raw.toByteArray(), MarkdownSource.decode(raw.toByteArray()).bytes())
    }

    @Test fun `local edits leave other newline spellings untouched`() {
        val source = MarkdownSource.decode("a\r\nb\nc\r\n".toByteArray())
        assertEquals("a\r\n中文\nc\r\n", String(source.edited("a\n中文\nc\n").bytes()))
        assertEquals("a\r\nb\nnew\r\nc\r\n", String(source.edited("a\nb\nnew\nc\n").bytes()))
        assertEquals("a\r\nc\r\n", String(source.edited("a\nc\n").bytes()))
    }

    @Test fun `malformed UTF8 is rejected rather than replaced`() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            MarkdownSource.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }
    }

    @Test fun `editable bounds include UTF8 size and logical lines without truncating recovery`() {
        assertTrue(MarkdownSource.editableText("x".repeat(MarkdownSource.MAX_EDIT_BYTES)))
        val withBom = "\uFEFF" + "x".repeat(MarkdownSource.MAX_EDIT_BYTES - 1) + "\r\n"
        assertTrue(MarkdownSource.decode(withBom.toByteArray()).editable)
        assertFalse(MarkdownSource.editableText("中".repeat(100_000)))
        assertTrue(MarkdownSource.editableText("\n".repeat(1_999)))
        assertFalse(MarkdownSource.editableText("\n".repeat(2_000)))
        val large = "line\n".repeat(3_000).toByteArray()
        val recovery = MarkdownSource.decode(large)
        assertFalse(recovery.editable)
        assertArrayEquals(large, recovery.bytes())
    }

    @Test fun `empty and no final newline remain unchanged`() {
        for (raw in listOf("", "\uFEFF", "# 你好 😀", "line  "))
            assertArrayEquals(raw.toByteArray(), MarkdownSource.decode(raw.toByteArray()).bytes())
    }

    @Test fun `format selection toggles without consuming surrounding text`() {
        val once = MarkdownEdit.wrap("a 中文 z", 2, 4, "**")
        assertEquals("a **中文** z", once.text)
        assertEquals("中文", once.text.substring(once.start, once.end))
        val twice = MarkdownEdit.wrap(once.text, once.start, once.end, "**")
        assertEquals("a 中文 z", twice.text)
        assertEquals("中文", twice.text.substring(twice.start, twice.end))
    }

    @Test fun `empty and reversed selections keep the insertion point inside syntax`() {
        assertEquals(MarkdownEdit("**", 1), MarkdownEdit.wrap("", 0, 0, "*"))
        val edit = MarkdownEdit.wrap("😀abc", 5, 2, "[", "](url)")
        assertEquals("😀[abc](url)", edit.text)
        assertEquals("abc", edit.text.substring(edit.start, edit.end))
    }

    @Test fun `multiline operation excludes an unselected following line`() {
        val edit = MarkdownEdit.lines("one\ntwo\nthree", 0, 8, "> ")
        assertEquals("> one\n> two\nthree", edit.text)
        assertEquals("one\ntwo\nthree", MarkdownEdit.lines(edit.text, edit.start, edit.end, "> ").text)
    }

    @Test fun `enter continues tasks unchecked and numbers with the original delimiter`() {
        val task = MarkdownEdit.enter("  - [x] done", 12)!!
        assertEquals("  - [x] done\n  - [ ] ", task.text)
        assertEquals("9) item\n10) ", MarkdownEdit.enter("9) item", 7)!!.text)
        assertEquals("> quote\n> ", MarkdownEdit.enter("> quote", 7)!!.text)
        assertEquals("", MarkdownEdit.enter("- [ ] ", 6)!!.text)
    }

    @Test fun `enter inside fenced code leaves text editing to the field`() {
        assertNull(MarkdownEdit.enter("```md\n- text", 12))
        assertNull(MarkdownEdit.enter("~~~\n- text", 10))
        val closed = "```\nx\n```\n- text"
        assertEquals("$closed\n- ", MarkdownEdit.enter(closed, closed.length)!!.text)
    }

    @Test fun `source search respects exact whitespace and UTF16 ranges`() {
        assertTrue(MarkdownEdit.matches("a  b", "a b", false).isEmpty())
        assertEquals(listOf(2..4, 6..8), MarkdownEdit.matches("😀Abc abc", "abc", false))
        assertEquals(listOf(6..8), MarkdownEdit.matches("😀Abc abc", "abc", true))
        assertTrue(MarkdownEdit.matches("abc", "", false).isEmpty())
    }

    @Test fun `replace all preserves untouched text and refuses oversized expansions`() {
        val text = "😀 Abc\nabc abc!"
        val edit = MarkdownEdit.replaceAll(text, MarkdownEdit.matches(text, "abc", false), "替换", 2)!!
        assertEquals("😀 替换\n替换 替换!", edit.text)
        assertEquals(2, edit.start)
        val common = "a".repeat(10_000)
        assertNull(MarkdownEdit.replaceAll(common, MarkdownEdit.matches(common, "a", true), "x".repeat(100), 0))
    }

    @Test fun `tab always adds indentation and shift tab changes every selected line`() {
        val initial = "  one\n    two\nthree"
        val indent = MarkdownEdit.indent(initial, 2, 14, false)
        assertEquals("      one\n        two\nthree", indent.text)
        val outdent = MarkdownEdit.indent(indent.text, indent.start, indent.end, true)
        assertEquals(initial, outdent.text)
        assertEquals(2, outdent.start)
        assertEquals(14, outdent.end)
        assertEquals(MarkdownEdit("a", 0), MarkdownEdit.indent("    a", 2, 2, true))
    }

    @Test fun `Markdown aliases are recognized and keep their suffix`() {
        assertEquals(DocumentKind.MARKDOWN, DocumentKind.ofName("note.MARKDOWN"))
        assertEquals("note", DocumentKind.stripSuffix("note.MARKDOWN"))
        assertEquals("note.MARKDOWN", DocumentKind.withSuffix("note.MARKDOWN", DocumentKind.MARKDOWN))
        assertEquals("note.md", DocumentKind.withSuffix("note", DocumentKind.MARKDOWN))
        assertNull(DocumentKind.ofName("file.md.bak"))
    }

    @Test fun `PDF batch planning skips Markdown inside picked directories`() {
        val entries = ExportZip.plan(listOf(ExportZip.Node("folder", "f", "f", true, listOf(
            ExportZip.Node("note.xnote", "n", "n", false), ExportZip.Node("text.md", "m", "m", false)))))
        assertEquals(listOf("folder/", "folder/note.pdf"), entries.map { it.path })
    }
}
