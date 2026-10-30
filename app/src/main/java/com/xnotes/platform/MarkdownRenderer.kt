package com.xnotes.platform

import androidx.annotation.WorkerThread
import com.xnotes.core.model.Rgba
import com.xnotes.core.text.CodeHighlighter
import com.xnotes.core.text.HighlightTheme
import com.xnotes.core.text.MarkdownPreview
import com.xnotes.core.text.MarkdownReader
import com.xnotes.core.text.MarkdownScienceLabels
import com.xnotes.settings.MarkdownPdfStyle
import com.xnotes.settings.MarkdownStyle
import com.xnotes.ui.theme.Palette

/** Immutable appearance snapshot. Workers never reach back into the live editor for settings. */
class MarkdownRenderer(
    private val readingStyle: MarkdownStyle,
    private val palette: Palette,
    private val highlighter: CodeHighlighter?,
    private val codeTheme: HighlightTheme?,
    private val labels: MarkdownScienceLabels,
) {
    @WorkerThread
    fun render(text: String): MarkdownPreview = render(text, null)

    /** Reading, thumbnails and printing resolve code/math appearance through the same renderer. */
    @WorkerThread
    fun printPage(text: String, options: MarkdownPdfStyle): String {
        val pdf = options.clamped()
        return MarkdownPage.html(render(text, pdf).html, readingStyle, palette, print = true, pdf = pdf)
    }

    private fun render(text: String, pdf: MarkdownPdfStyle?): MarkdownPreview {
        val style = pdf?.readingStyle(readingStyle) ?: readingStyle
        val colors = MarkdownPage.colors(style, palette)
        val dark = Rgba.fromHex(style.codeBackground)?.let { it.luminance() < .5 } ?: colors.dark
        val theme = if (pdf != null && pdf.colorMode != "reading") HighlightTheme.LIGHT
            else codeTheme ?: if (dark) HighlightTheme.DARK else HighlightTheme.LIGHT
        val math = MarkdownMath(style.fontSize, Rgba.fromHex(colors.text) ?: palette.text, style.mathScale / 100.0)
        return MarkdownReader.render(text, highlighter, theme, math::render, labels)
    }
}
