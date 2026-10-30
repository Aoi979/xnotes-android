package com.xnotes.core.text

import org.junit.Assert.*
import org.junit.Test

class MarkdownStructureTest {
    @Test fun `all six heading levels replace the old level without stacking markers`() {
        for (level in 1..6) {
            val edit = MarkdownBlocks.heading("### 中文 **标题**\n\nbody", 6, 6, level)!!
            assertEquals("${"#".repeat(level)} 中文 **标题**\n\nbody", edit.text)
            assertEquals(level, MarkdownBlocks.headingLevel(edit.text, edit.start))
        }
    }
    @Test fun `paragraph removes heading syntax and closing hashes but keeps inline formatting`() {
        assertEquals("**标题**\n", MarkdownBlocks.heading("## **标题** ##\n", 5, 5, 0)!!.text)
    }
    @Test fun `heading conversion preserves selection direction and excludes following line`() {
        val edit = MarkdownBlocks.heading("one\ntwo\nthree", 8, 0, 2)!!
        assertEquals("## one\n## two\nthree", edit.text)
        assertTrue(edit.start > edit.end)
        assertEquals("one\n## two\n", edit.text.substring(edit.end, edit.start))
    }
    @Test fun `setext headings become ATX and nested quote syntax survives`() {
        assertEquals("### 标题\n\nbody", MarkdownBlocks.heading("标题\n===\n\nbody", 1, 1, 3)!!.text)
        assertEquals("> # quote\n", MarkdownBlocks.heading("> ## quote\n", 7, 7, 1)!!.text)
    }
    @Test fun `format commands leave fenced code and literal HTML unchanged`() {
        val code = "```md\n## literal\n```"
        assertNull(MarkdownBlocks.heading(code, 9, 9, 1))
        assertNull(MarkdownBlocks.heading("<div>\ntext\n</div>", 8, 8, 2))
        assertEquals("###### ", MarkdownBlocks.heading("", 0, 0, 6)!!.text)
    }
    @Test fun `table lookup preserves raw inline cells escaped pipes and alignments`() {
        val raw = "before\n\n| 名称 | 值 |\n| :--- | ---: |\n| **a** | `a\\|b` |\n\nafter"
        val found = MarkdownTable.at(raw, raw.indexOf("**a**"))!!
        assertEquals(listOf(ParaAlign.LEFT, ParaAlign.RIGHT), found.table.alignments)
        assertEquals(listOf("**a**", "`a\\|b`"), found.table.rows[1])
        val edit = found.replace(raw, found.table.insertRow(2).insertColumn(1))!!
        assertTrue(edit.text.startsWith("before\n\n")); assertTrue(edit.text.endsWith("\n\nafter"))
        val reopened = MarkdownTable.at(edit.text, edit.start)!!.table
        assertEquals("`a\\|b`", reopened.rows[1][2]); assertEquals(3, reopened.rows.size)
    }
    @Test fun `new tables split inline text at block boundaries and select first header`() {
        val table = MarkdownTable.empty(3, 4).cell(0, 0, "项目")
        val edit = MarkdownTable.insert("left right", 4, 4, table)
        assertTrue(edit.text.startsWith("left\n\n| 项目")); assertTrue(edit.text.endsWith("\n\n right"))
        assertEquals("项目", edit.text.substring(edit.start, edit.end))
        assertEquals(4, MarkdownTable.at(edit.text, edit.start)!!.table.columns)
    }
    @Test fun `table mutations keep other cells and column alignments attached`() {
        val table = MarkdownTable.empty(3, 2).cell(1, 1, "保留").align(1, ParaAlign.CENTER)
        val next = table.insertColumn(0).removeColumn(1).insertRow(1).removeRow(1)
        assertEquals("保留", next.rows[1][1]); assertEquals(ParaAlign.CENTER, next.alignments[1])
        assertEquals(next, next.removeRow(0)); assertEquals(1, MarkdownTable.empty(1, 1).removeColumn(0).columns)
    }
    @Test fun `table editing escapes new pipes without double escaping existing pipes`() {
        val table = MarkdownTable.empty(2, 2).cell(0, 0, "a|b").cell(1, 1, "c\\|d")
        val raw = table.markdown()
        assertTrue(raw.contains("a\\|b")); assertTrue(raw.contains("c\\|d")); assertFalse(raw.contains("c\\\\|d"))
        assertEquals(2, MarkdownTable.at(raw, 2)!!.table.columns)
    }
    @Test fun `table lookup ignores fenced examples and rejects stale replacements`() {
        val raw = MarkdownTable.empty(2, 2).markdown()
        assertNull(MarkdownTable.at("```md\n$raw\n```", 12))
        val region = MarkdownTable.at(raw, 3)!!
        assertNull(region.replace(raw.replace("---", ":--"), region.table))
    }
    @Test fun `quote tables retain quote prefixes and ragged rows do not lose extra cells`() {
        val raw = "> | a | b |\n> | --- | --- |\n> | x | y |"
        val region = MarkdownTable.at(raw, raw.indexOf("x"))!!
        assertTrue(region.replace(raw, region.table.cell(1, 1, "z"))!!.text.endsWith("> | x | z |"))
        assertNull(MarkdownTable.at("| a | b |\n| --- | --- |\n| 1 | 2 | extra |", 30))
    }
}
