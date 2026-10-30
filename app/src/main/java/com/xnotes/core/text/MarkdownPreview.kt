package com.xnotes.core.text

import org.commonmark.node.*
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URLDecoder
import java.net.URLEncoder

data class MarkdownHeading(val title: String, val level: Int, val offset: Int, val anchor: String)
data class MarkdownHighlight(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { HEADING, STRONG, EMPHASIS, CODE, LINK, MUTED, MARK }
}
data class MarkdownPreview(val html: String, val headings: List<MarkdownHeading>, val highlights: List<MarkdownHighlight>)
data class MarkdownFormula(val image: String, val width: Double, val height: Double, val descent: Double)
data class MarkdownScienceLabels(
    val formulaError: String = "Formula unavailable; source shown",
    val diagramSource: String = "Mermaid source",
    val diagramError: String = "Diagram unavailable; source shown",
)

/** One AST for the source decorations, outline and reader. It never serializes the document. */
object MarkdownReader {
    const val ORIGIN = "https://xnotes-markdown.invalid"
    const val BASE = "$ORIGIN/document/"

    fun render(text: String, highlighter: CodeHighlighter? = null, theme: HighlightTheme = HighlightTheme.LIGHT,
               formula: ((String, Boolean) -> MarkdownFormula?)? = null,
               scienceLabels: MarkdownScienceLabels = MarkdownScienceLabels()): MarkdownPreview {
        val root = MarkdownParser.sourceTree(text)
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        val headings = ArrayList<MarkdownHeading>()
        val highlights = ArrayList<MarkdownHighlight>()
        val anchors = HashMap<Node, String>()
        val used = HashSet<String>()
        fun children(n: Node, block: (Node) -> Unit) {
            var c = n.firstChild
            while (c != null) { block(c); c = c.next }
        }
        fun plain(n: Node): String = when (n) {
            is Text -> n.literal
            is Code -> n.literal
            is MarkdownParser.MathInline -> n.latex
            else -> buildString { children(n) { append(plain(it)) } }
        }
        fun start(n: Node): Int? = n.sourceSpans.firstOrNull()?.let { s -> lineStarts.getOrNull(s.lineIndex)?.plus(s.columnIndex) }
        fun visit(n: Node) {
            val first = start(n)
            val last = n.sourceSpans.lastOrNull()?.let { s -> lineStarts.getOrNull(s.lineIndex)?.plus(s.columnIndex + s.length) }
            if (n is Heading && first != null) {
                val title = plain(n)
                val stem = title.lowercase().replace(Regex("[^\\p{L}\\p{N}_ -]"), "").replace(' ', '-').ifBlank { "section" }
                var slug = stem
                var count = 1
                while (!used.add(slug)) slug = "$stem-${count++}"
                anchors[n] = slug
                headings += MarkdownHeading(title, n.level, first, slug)
            }
            val kind = when (n) {
                is Heading -> MarkdownHighlight.Kind.HEADING
                is StrongEmphasis -> MarkdownHighlight.Kind.STRONG
                is Emphasis -> MarkdownHighlight.Kind.EMPHASIS
                is Code, is FencedCodeBlock, is IndentedCodeBlock, is MarkdownParser.MathBlock,
                is MarkdownParser.MathInline -> MarkdownHighlight.Kind.CODE
                is Link, is Image -> MarkdownHighlight.Kind.LINK
                is HtmlBlock, is HtmlInline, is ThematicBreak -> MarkdownHighlight.Kind.MUTED
                is MarkdownParser.Marked -> MarkdownHighlight.Kind.MARK
                else -> null
            }
            if (first != null && last != null && kind != null && last > first) highlights += MarkdownHighlight(first, last, kind)
            children(n, ::visit)
        }
        visit(root)
        val renderer = HtmlRenderer.builder().extensions(MarkdownParser.extensions)
            .escapeHtml(true).sanitizeUrls(true)
            .attributeProviderFactory { _ -> org.commonmark.renderer.html.AttributeProvider { node, _, attrs ->
                anchors[node]?.let { attrs["id"] = it }
                if (node is Link) {
                    val target = node.destination
                    if (!target.startsWith("#") && MarkdownParser.linkTarget(target) == null) attrs.remove("href")
                }
            } }
            .nodeRendererFactory { context -> object : NodeRenderer {
                override fun getNodeTypes(): Set<Class<out Node>> = setOf(
                    FencedCodeBlock::class.java, IndentedCodeBlock::class.java, Image::class.java,
                    MarkdownParser.Marked::class.java, MarkdownParser.MathBlock::class.java, MarkdownParser.MathInline::class.java,
                )
                override fun render(node: Node) {
                    val out = context.writer
                    fun math(latex: String, display: Boolean, block: Boolean) {
                        val image = formula?.invoke(latex, display)
                        if (image != null) {
                            val wrapper = if (block) "div" else "span"
                            if (display) out.tag(wrapper, mapOf("class" to "math-display"))
                            out.tag("img", mapOf("class" to if (display) "math-block" else "math-inline", "src" to image.image,
                                "alt" to latex, "title" to latex,
                                "style" to "width:${image.width}em;height:${image.height}em;vertical-align:-${image.descent}em"), true)
                            if (display) out.tag("/$wrapper")
                        } else {
                            if (block) {
                                out.tag("details", mapOf("class" to "science-error", "open" to ""))
                                out.tag("summary"); out.text(scienceLabels.formulaError); out.tag("/summary"); out.tag("pre")
                            }
                            out.tag("code", mapOf("class" to "math-source", "title" to scienceLabels.formulaError))
                            out.text((if (display) "$$" else "$") + (if (block) "\n" else "") + latex +
                                (if (block) "\n" else "") + if (display) "$$" else "$")
                            out.tag("/code")
                            if (block) { out.tag("/pre"); out.tag("/details") }
                        }
                    }
                    when (node) {
                        is MarkdownParser.Marked -> { out.tag("mark"); children(node, context::render); out.tag("/mark") }
                        is MarkdownParser.MathInline -> math(node.source, node.display, false)
                        is MarkdownParser.MathBlock -> math(node.source, true, true)
                        is Image -> {
                            val path = resourcePath(node.destination)
                            val alt = plain(node).ifBlank { node.destination }
                            if (path == null) {
                                out.tag("span", mapOf("class" to "missing-image")); out.text("[$alt]"); out.tag("/span")
                            } else {
                                out.tag("img", mapOf("src" to "$ORIGIN/images/${encodePath(path)}", "alt" to alt, "title" to node.destination), true)
                            }
                        }
                        else -> {
                            val value = if (node is FencedCodeBlock) node.literal else (node as IndentedCodeBlock).literal
                            val language = if (node is FencedCodeBlock) node.info.trim().substringBefore(' ') else ""
                            if (language.equals("mermaid", ignoreCase = true)) {
                                out.tag("figure", mapOf("class" to "md-diagram", "data-error" to scienceLabels.diagramError))
                                out.tag("details", mapOf("class" to "diagram-source", "open" to ""))
                                out.tag("summary"); out.text(scienceLabels.diagramSource); out.tag("/summary")
                                out.tag("pre"); out.tag("code"); out.text(value); out.tag("/code"); out.tag("/pre")
                                out.tag("/details"); out.tag("/figure")
                                return
                            }
                            val lang = MarkdownParser.normalizeLang(language.lowercase())
                            out.tag("pre"); out.tag("code")
                            val spans = if (value.length <= 128 * 1024) highlighter?.highlight(value, lang).orEmpty() else emptyList()
                            var cursor = 0
                            for (s in spans.sortedWith(compareBy({ it.start }, { it.end }))) {
                                if (s.start < cursor || s.end > value.length || s.end <= s.start) continue
                                val color = theme.colorFor(s.capture) ?: continue
                                out.text(value.substring(cursor, s.start))
                                out.tag("span", mapOf("style" to "color:rgb(${color.r},${color.g},${color.b})"))
                                out.text(value.substring(s.start, s.end)); out.tag("/span")
                                cursor = s.end
                            }
                            out.text(value.substring(cursor)); out.tag("/code"); out.tag("/pre")
                        }
                    }
                }
            } }.build()
        return MarkdownPreview(renderer.render(root), headings, highlights)
    }

    /** Decode exactly once and constrain every request to a relative document directory. */
    fun resourcePath(destination: String): List<String>? = runCatching {
        val path = URLDecoder.decode(destination.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        if (path.startsWith('/') || path.contains('\\') || path.any { it < ' ' } || ':' in path) return null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) null else segments
    }.getOrNull()

    private fun encodePath(path: List<String>): String = path.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
