package com.xnotes.platform

import com.xnotes.core.text.MarkdownReader
import com.xnotes.settings.MarkdownStyle
import com.xnotes.settings.MarkdownPdfStyle
import com.xnotes.ui.theme.Palette

/** One stylesheet for reader, home thumbnails and native HTML-to-PDF printing. */
object MarkdownPage {
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    fun hasDiagrams(page: String) = page.contains("data-xnotes-science=\"true\"")
    data class Colors(val paper: String, val text: String, val accent: String, val surface: String, val border: String, val muted: String, val dark: Boolean)

    fun colors(style: MarkdownStyle, palette: Palette): Colors {
        fun rgb(c: com.xnotes.core.model.Rgba) = "rgb(${c.r},${c.g},${c.b})"
        var base = when (style.theme) {
            "light" -> Colors("#ffffff", "#202124", "#315da8", "#f1f3f6", "#d1d5dc", "#5c626d", false)
            "sepia" -> Colors("#f7f0e2", "#40382b", "#87532b", "#eee4d0", "#cdbfa5", "#756a59", false)
            "dark" -> Colors("#17191e", "#e3e5eb", "#b3c7ff", "#242731", "#414650", "#b3b8c3", true)
            else -> Colors(rgb(palette.paper), rgb(palette.text), rgb(palette.accent), rgb(palette.surface), rgb(palette.border), rgb(palette.textDim), palette.isDark)
        }
        // "Automatic" text remains readable when only the page color is customized.
        com.xnotes.core.model.Rgba.fromHex(style.background)?.let { paper ->
            val dark = paper.luminance() < .5
            if (dark != base.dark) base = if (dark)
                Colors(base.paper, "#e3e5eb", "#b3c7ff", "#242731", "#414650", "#b3b8c3", true)
            else Colors(base.paper, "#202124", "#315da8", "#f1f3f6", "#d1d5dc", "#5c626d", false)
        }
        return base.copy(paper = style.background ?: base.paper, text = style.foreground ?: base.text, accent = style.accent ?: base.accent,
            border = style.borderColor ?: base.border)
    }

    fun html(body: String, settings: MarkdownStyle, palette: Palette, print: Boolean = false, pdf: MarkdownPdfStyle = MarkdownPdfStyle.DEFAULT): String {
        val p = pdf.clamped()
        val s = (if (print) p.readingStyle(settings) else settings).clamped()
        val c = colors(s, palette)
        val heading = s.headingColor ?: c.text
        val code = s.codeBackground ?: c.surface
        val quote = s.quoteBackground ?: c.surface
        fun ink(background: String?, fallback: String): String = com.xnotes.core.model.Rgba.fromHex(background)?.let {
            if (it.luminance() < .5) "#e3e5eb" else "#202124"
        } ?: fallback
        val codeInk = ink(s.codeBackground, c.text)
        val quoteInk = ink(s.quoteBackground, c.text)
        val diagrams = body.contains("<figure class=\"md-diagram\"")
        val id = sequence.incrementAndGet()
        val diagramColors = org.json.JSONObject().apply {
            put("darkMode", c.dark); put("background", c.paper); put("primaryColor", c.surface)
            put("primaryTextColor", c.text); put("textColor", c.text); put("primaryBorderColor", c.accent)
            put("lineColor", c.text); put("secondaryColor", c.paper); put("tertiaryColor", c.surface)
            put("fontFamily", s.font); put("fontSize", if (print) "${p.bodyPt * 4 / 3}px" else "${s.fontSize}px")
        }.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """
            <!doctype html><html data-xnotes-science="$diagrams" data-xnotes-page="$id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${MarkdownReader.ORIGIN} data:; script-src ${if (diagrams) "${MarkdownReader.ORIGIN}/science/" else "'none'"}; base-uri 'none'; form-action 'none'">
            <meta name="xnotes-diagram-colors" content="$diagramColors">
            <style>
            :root{color-scheme:${if (c.dark) "dark" else "light"};background:${c.paper};--pdf-stripe:${c.surface}}
            *{box-sizing:border-box}body{margin:0 auto;padding:${s.padding}px;max-width:${s.contentWidth}px;color:${c.text};background:${c.paper};font:${s.fontSize}px/${s.lineHeight} ${s.font};overflow-wrap:anywhere;text-align:${if (s.justified) "justify" else "start"}}
            p{margin:0 0 ${s.paragraphGap}px}h1,h2,h3,h4,h5,h6{color:$heading;line-height:1.3;margin:${s.headingGap}px 0 ${s.paragraphGap}px;font-weight:${if (s.headingBold) 700 else 500};break-after:avoid}
            ${s.headingSizes.mapIndexed { i, size -> "h${i + 1}{font-size:${size}px}" }.joinToString("")}
            h1,h2{${if (s.headingRules) "border-bottom:1px solid ${c.border};padding-bottom:.4em" else "border:0"}}
            a{color:${c.accent};text-decoration:${if (s.linkUnderline) "underline" else "none"}}
            pre{margin:${s.paragraphGap}px 0;padding:${s.codePadding}px;overflow:auto;background:$code;color:$codeInk;border-radius:${s.codeRadius}px;white-space:${if (s.codeWrap) "pre-wrap" else "pre"};overflow-wrap:${if (s.codeWrap) "anywhere" else "normal"};text-align:start}
            code{font:${s.codeSize}px/1.6 monospace;background:$code;color:$codeInk;padding:.12em .3em;border-radius:${s.codeRadius / 3}px}pre code{padding:0;background:none;white-space:inherit}
            blockquote{margin:${s.paragraphGap}px 0;padding:12px 16px;border-inline-start:${s.quoteBorder}px solid ${c.accent};background:$quote;color:$quoteInk}blockquote p:last-child{margin-bottom:0}
            ul,ol{padding-inline-start:1.8em}li{margin:.25em 0}table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:${s.paragraphGap}px 0;text-align:start}
            td,th{border:${if (s.tableBorders) "1px solid ${c.border}" else "0"};padding:${s.tablePadding}px;min-width:48px}th{background:${c.surface};font-weight:700}
            ${if (s.tableStripes) "tbody tr:nth-child(even){background:${c.surface}}" else ""}
            img{display:block;max-width:${s.imageWidth}%;height:auto;margin:${s.paragraphGap}px auto;border-radius:${s.imageRadius}px}
            img.math-inline,img.math-block{display:inline-block;max-width:none;margin:0;border-radius:0;object-fit:contain}
            .math-display{display:block;overflow:auto;text-align:center;margin:${s.paragraphGap}px 0;padding:.25em 0;break-inside:avoid}
            .md-diagram{margin:${s.paragraphGap}px 0;text-align:start}.diagram-drawing{overflow:auto;padding:.5em 0;break-inside:avoid}
            img.diagram-image{display:block;height:auto;max-width:${if (s.diagramFit) "100%" else "none"};margin:0 auto;border-radius:0;object-fit:contain}
            .diagram-source{color:${c.muted};font-size:.875em}.diagram-source summary,.science-error summary{cursor:pointer;padding:.5em 0}
            .science-error{color:${c.muted};border-inline-start:3px solid ${c.border};padding-inline-start:12px}
            hr{border:0;border-top:1px solid ${c.border};margin:${s.headingGap}px 0}mark{background:${c.accent};color:${c.paper};padding:0 .1em}.missing-image{color:${c.muted}}input{pointer-events:none;accent-color:${c.accent}}
            ${if (print) printCss(p) else ""}
            </style></head><body>$body${if (diagrams) "<script src=\"${MarkdownReader.ORIGIN}/science/bootstrap.js\"></script>" else ""}</body></html>
        """.trimIndent()
    }

    private fun printCss(p: MarkdownPdfStyle): String = """
        @media print {
          html,body{max-width:none!important;padding:0!important;margin:0!important;-webkit-print-color-adjust:exact;print-color-adjust:exact}
          body{font-family:${p.font};font-size:${p.bodyPt}pt;line-height:${p.lineHeight};text-align:${if (p.justified) "justify" else "start"}}
          p{margin:${p.paragraphBeforePt}pt 0 ${p.paragraphAfterPt}pt;orphans:${p.widowLines};widows:${p.widowLines}}
          body>p{text-indent:${p.indentEm}em}
          ${if (!p.indentAfterHeading) (1..6).joinToString(",") { "h$it+p" } + "{text-indent:0}" else ""}
          h1,h2,h3,h4,h5,h6{line-height:${p.headingLineHeight};font-weight:${if (p.headingBold) 700 else 400};break-after:${if (p.keepHeading) "avoid" else "auto"};page-break-after:${if (p.keepHeading) "avoid" else "auto"}}
          ${p.headingPt.indices.joinToString("") { i -> "h${i + 1}{font-size:${p.headingPt[i]}pt;margin:${p.headingBeforePt[i]}pt 0 ${p.headingAfterPt[i]}pt}" }}
          ${(1..6).joinToString(",") { "body>h$it:first-child" }}{margin-top:0}
          h1,h2{${if (p.headingRules) "border-bottom-width:.5pt;border-bottom-style:solid;padding-bottom:.3em" else "border-bottom:0;padding-bottom:0"}}
          ${if (p.headingPageBreak != "none") "${p.headingPageBreak}:not(:first-child){break-before:page;page-break-before:always}" else ""}
          ul,ol{margin:${p.blockGapPt}pt 0;padding-inline-start:${p.listIndentEm}em}li{margin:${p.listGapPt}pt 0;orphans:${p.widowLines};widows:${p.widowLines}}li>p{margin:0}
          pre{font-size:${p.codePt}pt;line-height:${p.codeLineHeight};margin:${p.blockGapPt}pt 0;padding:${p.codePaddingPt}pt;border-radius:4pt;white-space:${if (p.codeWrap) "pre-wrap" else "pre"};overflow:visible;overflow-wrap:${if (p.codeWrap) "anywhere" else "normal"}}
          code{font-size:${p.codePt}pt;line-height:${p.codeLineHeight}}pre code{white-space:inherit}
          blockquote{margin:${p.blockGapPt}pt 0;padding:${p.quotePaddingPt}pt;border-inline-start-width:${p.quoteRulePt}pt}blockquote p:last-child{margin-bottom:0}
          table{display:table;width:100%;max-width:100%;overflow:visible;margin:${p.blockGapPt}pt 0;font-size:${p.tablePt}pt;line-height:${p.lineHeight}}
          td,th{min-width:0;padding:${p.tablePaddingPt}pt;border-width:${if (p.tableBorders) ".5pt" else "0"};border-style:solid}
          tbody tr:nth-child(even){background:${if (p.tableStripes) "var(--pdf-stripe)" else "transparent"}}
          thead{display:${if (p.repeatTableHeader) "table-header-group" else "table-row-group"}}tr{break-inside:${if (p.keepTableRows) "avoid" else "auto"};page-break-inside:${if (p.keepTableRows) "avoid" else "auto"}}
          img:not(.math-inline):not(.math-block){max-width:${p.imageWidth}%;max-height:${p.imageHeight}vh;object-fit:contain;margin:${p.blockGapPt}pt auto;break-inside:avoid}
          .md-diagram,.math-display{margin:${p.blockGapPt}pt 0;text-indent:0}.diagram-drawing{padding:0;overflow:visible}
          .diagram-source:not([open]){display:none}.math-display{overflow:visible}.math-display img{max-width:100%;height:auto!important}
          .science-error pre{white-space:pre-wrap;overflow-wrap:anywhere}a{overflow-wrap:anywhere}
          ${if (p.colorMode == "mono") "pre span,a{color:inherit!important}" else ""}
        }
    """.trimIndent()
}
