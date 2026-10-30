package com.xnotes.platform

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.text.CodeHighlighter
import com.xnotes.core.text.FlowLayout
import com.xnotes.core.text.HighlightTheme
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.TextFlow

/** A fixed appearance for private layout passes; never consults the live editor while painting. */
internal class FlowRenderStyle(
    private val textMeasurer: TextMeasurer,
    private val highlighter: CodeHighlighter?,
    private val theme: HighlightTheme,
    private val textColor: Rgba,
    private val accent: Rgba,
    private val rule: Rgba,
) {
    fun layout(flow: TextFlow): FlowLayout {
        val layout = FlowLayout(textMeasurer, MathRendering.Blocking)
        layout.codeBackground = { theme.background }
        layout.autoColor = { textColor }
        layout.accentColor = { accent }
        layout.ruleColor = { rule }
        val hl = highlighter ?: return layout
        val spans = HashMap<Paragraph, List<com.xnotes.core.text.CodeSpan>>()
        var i = 0
        while (i < flow.paragraphs.size) {
            val lang = flow.paragraphs[i].codeLang
            if (lang.isNullOrEmpty() || !hl.supports(lang)) {
                i++
                continue
            }
            val start = i
            while (i < flow.paragraphs.size && flow.paragraphs[i].codeLang == lang) i++
            val paras = flow.paragraphs.subList(start, i)
            // Contiguous same-language paragraphs parse as ONE text, like the live highlighter.
            val sorted = hl.highlight(paras.joinToString("\n") { it.plainText() }, lang)
                ?.sortedBy { it.start } ?: continue
            var offset = 0
            for (p in paras) {
                val len = p.length
                spans[p] = sorted.mapNotNull { s ->
                    val a = (s.start - offset).coerceAtLeast(0)
                    val b = (s.end - offset).coerceAtMost(len)
                    if (b > a) theme.colorFor(s.capture)?.let { com.xnotes.core.text.CodeSpan(a, b, it) } else null
                }
                offset += len + 1
            }
        }
        layout.codeSpans = { spans[it] }
        return layout
    }
}
