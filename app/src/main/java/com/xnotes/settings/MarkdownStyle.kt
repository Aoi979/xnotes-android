package com.xnotes.settings

import org.json.JSONArray
import org.json.JSONObject

/** Personal screen reading appearance
never serialized into the Markdown document. */
data class MarkdownStyle(
    val theme: String = "system",
    val font: String = "sans-serif",
    val fontSize: Int = 16,
    val lineHeight: Double = 1.65,
    val paragraphGap: Int = 16,
    val contentWidth: Int = 800,
    val padding: Int = 24,
    val justified: Boolean = false,
    val headingSizes: List<Int> = listOf(32, 28, 24, 20, 18, 16),
    val headingGap: Int = 28,
    val headingBold: Boolean = true,
    val headingRules: Boolean = true,
    val foreground: String? = null,
    val background: String? = null,
    val headingColor: String? = null,
    val accent: String? = null,
    val codeBackground: String? = null,
    val quoteBackground: String? = null,
    val borderColor: String? = null,
    val linkUnderline: Boolean = true,
    val codeSize: Int = 14,
    val codeWrap: Boolean = false,
    val codeRadius: Int = 12,
    val codePadding: Int = 16,
    val quoteBorder: Int = 3,
    val tablePadding: Int = 10,
    val tableBorders: Boolean = true,
    val tableStripes: Boolean = false,
    val imageWidth: Int = 100,
    val imageRadius: Int = 0,
    val mathScale: Int = 100,
    val diagramFit: Boolean = true,
) {
    fun clamped(): MarkdownStyle = copy(
        theme = theme.takeIf { it in themeIds } ?: "system",
        font = font.takeIf { it in fontIds } ?: "sans-serif",
        fontSize = fontSize.coerceIn(12, 28),
        lineHeight = lineHeight.takeIf { it.isFinite() }?.coerceIn(1.2, 2.4) ?: 1.65,
        paragraphGap = paragraphGap.coerceIn(4, 32),
        contentWidth = contentWidth.coerceIn(400, 1200),
        padding = padding.coerceIn(8, 64),
        headingSizes = List(6) { headingSizes.getOrElse(it) { DEFAULT.headingSizes[it] }.coerceIn(14, 56) },
        headingGap = headingGap.coerceIn(8, 48),
        foreground = color(foreground),
        background = color(background),
        headingColor = color(headingColor),
        accent = color(accent),
        codeBackground = color(codeBackground),
        quoteBackground = color(quoteBackground),
        borderColor = color(borderColor),
        codeSize = codeSize.coerceIn(10, 24),
        codeRadius = codeRadius.coerceIn(0, 24),
        codePadding = codePadding.coerceIn(4, 32),
        quoteBorder = quoteBorder.coerceIn(0, 8),
        tablePadding = tablePadding.coerceIn(4, 20),
        imageWidth = imageWidth.coerceIn(25, 100),
        imageRadius = imageRadius.coerceIn(0, 32),
        mathScale = mathScale.coerceIn(80, 180),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("theme", theme)
        put("font", font)
        put("fontSize", fontSize)
        put("lineHeight", lineHeight)
        put("paragraphGap", paragraphGap)
        put("contentWidth", contentWidth)
        put("padding", padding)
        put("justified", justified)
        put("headingSizes", JSONArray(headingSizes))
        put("headingGap", headingGap)
        put("headingBold", headingBold)
        put("headingRules", headingRules)
        put("foreground", foreground)
        put("background", background)
        put("headingColor", headingColor)
        put("accent", accent)
        put("codeBackground", codeBackground)
        put("quoteBackground", quoteBackground)
        put("borderColor", borderColor)
        put("linkUnderline", linkUnderline)
        put("codeSize", codeSize)
        put("codeWrap", codeWrap)
        put("codeRadius", codeRadius)
        put("codePadding", codePadding)
        put("quoteBorder", quoteBorder)
        put("tablePadding", tablePadding)
        put("tableBorders", tableBorders)
        put("tableStripes", tableStripes)
        put("imageWidth", imageWidth)
        put("imageRadius", imageRadius)
        put("mathScale", mathScale)
        put("diagramFit", diagramFit)
    }

    companion object {
        val DEFAULT = MarkdownStyle()
        private val themeIds = setOf("system", "light", "sepia", "dark")
        private val fontIds = setOf("sans-serif", "serif", "monospace")
        private val colorPattern = Regex("#[0-9a-fA-F]{6}")

        private fun color(value: String?): String? = value?.takeIf { colorPattern.matches(it) }
        fun fromJson(o: JSONObject?): MarkdownStyle {
            if (o == null) return DEFAULT
            fun int(key: String, default: Int) = o.optInt(key, default)
            fun bool(key: String, default: Boolean) = o.optBoolean(key, default)
            fun c(key: String) = color(o.optString(key))
            val h = o.optJSONArray("headingSizes")
            return MarkdownStyle(
                theme = o.optString("theme", "system"),
                font = o.optString("font", "sans-serif"),
                fontSize = int("fontSize", 16),
                lineHeight = o.optDouble("lineHeight", 1.65),
                paragraphGap = int("paragraphGap", 16),
                contentWidth = int("contentWidth", 800),
                padding = int("padding", 24),
                justified = bool("justified", false),
                headingSizes = List(6) { h?.optInt(it, DEFAULT.headingSizes[it]) ?: DEFAULT.headingSizes[it] },
                headingGap = int("headingGap", 28),
                headingBold = bool("headingBold", true),
                headingRules = bool("headingRules", true),
                foreground = c("foreground"),
                background = c("background"),
                headingColor = c("headingColor"),
                accent = c("accent"),
                codeBackground = c("codeBackground"),
                quoteBackground = c("quoteBackground"),
                borderColor = c("borderColor"),
                linkUnderline = bool("linkUnderline", true),
                codeSize = int("codeSize", 14),
                codeWrap = bool("codeWrap", false),
                codeRadius = int("codeRadius", 12),
                codePadding = int("codePadding", 16),
                quoteBorder = int("quoteBorder", 3),
                tablePadding = int("tablePadding", 10),
                tableBorders = bool("tableBorders", true),
                tableStripes = bool("tableStripes", false),
                imageWidth = int("imageWidth", 100),
                imageRadius = int("imageRadius", 0),
                mathScale = int("mathScale", 100),
                diagramFit = bool("diagramFit", true),
            ).clamped()
        }
    }
}
