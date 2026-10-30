package com.xnotes.core.text

import org.junit.Assert.*
import org.junit.Test

class MarkdownReaderTest {
    @Test fun `outline uses parsed headings and gives duplicates distinct anchors`() {
        val text = "# 测试\n\n```md\n# not a heading\n```\n\n## 测试\n"
        val preview = MarkdownReader.render(text)
        assertEquals(listOf("测试", "测试"), preview.headings.map { it.title })
        assertEquals(listOf(1, 2), preview.headings.map { it.level })
        assertEquals(listOf("测试", "测试-1"), preview.headings.map { it.anchor })
        assertEquals(text.indexOf("## 测试"), preview.headings[1].offset)
    }

    @Test fun `raw HTML and unsafe links cannot become executable preview content`() {
        val html = MarkdownReader.render("<script>alert(1)</script>\n\n[bad](javascript:alert%281%29)\n\n<img src=x onerror=alert(1)>").html
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test fun `GFM and project extensions share the existing parser`() {
        val html = MarkdownReader.render("| A | B |\n| --- | --- |\n| a | b |\n\n- [x] done\n\n~~old~~ ==mark== $" + "x^2$\n").html
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("checkbox"))
        assertTrue(html.contains("<del>old</del>"))
        assertTrue(html.contains("<mark>mark</mark>"))
        assertTrue(html.contains("x^2"))
    }

    @Test fun `local images use the isolated origin and remote images have no src`() {
        val html = MarkdownReader.render("![local](images/a%20b.png)\n\n![remote](https://example.com/p.png)").html
        assertTrue(html.contains("${MarkdownReader.ORIGIN}/images/images/a%20b.png"))
        assertFalse(html.contains("src=\"https://example.com"))
        assertTrue(html.contains("[remote]"))
    }

    @Test fun `resource paths reject decoded traversal absolute paths and content URIs`() {
        for (path in listOf("../x", "%2e%2e/x", "/x", "content://a/x", "https://a/x", "a/../x", "a%5cb"))
            assertNull(path, MarkdownReader.resourcePath(path))
        assertEquals(listOf("images", "a+b 空格.png"), MarkdownReader.resourcePath("images/a+b%20空格.png"))
    }

    @Test fun `code preview reuses captures and escapes source instead of running it`() {
        val highlighter = object : CodeHighlighter {
            override fun supports(language: String) = true
            override fun highlight(text: String, language: String) = listOf(HighlightSpan(0, 3, "keyword"))
        }
        val html = MarkdownReader.render("```kt\nval x = \"<b>\"\n```", highlighter).html
        assertTrue(html.contains("<span style=\"color:rgb("))
        assertTrue(html.contains("&lt;b&gt;"))
        assertFalse(html.contains("<b>"))
    }
}
