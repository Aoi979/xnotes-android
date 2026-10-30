package com.xnotes.settings

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Physical print typography, independent of the screen reader. Sizes are pt; page edges are mm. */
data class MarkdownPdfStyle(
    val paper: String = "a4", val landscape: Boolean = false,
    val customWidthMm: Double = 210.0, val customHeightMm: Double = 297.0,
    val topMm: Double = 18.0, val rightMm: Double = 18.0, val bottomMm: Double = 18.0, val leftMm: Double = 18.0,
    val font: String = "sans-serif", val bodyPt: Double = 11.0, val lineHeight: Double = 1.5,
    val paragraphBeforePt: Double = 0.0, val paragraphAfterPt: Double = 8.0, val indentEm: Double = 0.0,
    val justified: Boolean = false, val indentAfterHeading: Boolean = false,
    val headingPt: List<Double> = listOf(24.0, 20.0, 17.0, 14.0, 12.0, 11.0),
    val headingBeforePt: List<Double> = listOf(18.0, 16.0, 14.0, 12.0, 10.0, 8.0),
    val headingAfterPt: List<Double> = listOf(10.0, 8.0, 6.0, 5.0, 4.0, 4.0),
    val headingLineHeight: Double = 1.25, val headingBold: Boolean = true, val headingRules: Boolean = false,
    val blockGapPt: Double = 10.0, val listGapPt: Double = 3.0, val listIndentEm: Double = 1.8,
    val quotePaddingPt: Double = 10.0, val quoteRulePt: Double = 2.0,
    val codePt: Double = 9.0, val codeLineHeight: Double = 1.4, val codePaddingPt: Double = 8.0, val codeWrap: Boolean = true,
    val tablePt: Double = 10.0, val tablePaddingPt: Double = 5.0, val tableBorders: Boolean = true, val tableStripes: Boolean = false,
    val imageWidth: Int = 100, val imageHeight: Int = 80, val mathScale: Int = 100,
    val colorMode: String = "paper", val headingPageBreak: String = "none",
    val keepHeading: Boolean = true, val keepTableRows: Boolean = true, val repeatTableHeader: Boolean = true, val widowLines: Int = 2,
) {
    fun paperMm(): Pair<Double, Double> {
        val (a, b) = when (paper) { "a5" -> 148.0 to 210.0; "letter" -> 215.9 to 279.4; "legal" -> 215.9 to 355.6
            "custom" -> customWidthMm to customHeightMm; else -> 210.0 to 297.0 }
        return if (landscape) max(a, b) to min(a, b) else min(a, b) to max(a, b)
    }

    fun clamped(): MarkdownPdfStyle {
        fun number(v: Double, lo: Double, hi: Double, fallback: Double) = if (v.isFinite()) v.coerceIn(lo, hi) else fallback
        fun six(v: List<Double>, lo: Double, hi: Double, defaults: List<Double>) = List(6) { number(v.getOrElse(it) { defaults[it] }, lo, hi, defaults[it]) }
        val w = number(customWidthMm, 80.0, 500.0, 210.0); val h = number(customHeightMm, 80.0, 500.0, 297.0)
        val normalized = copy(paper = paper.takeIf { it in listOf("a4", "a5", "letter", "legal", "custom") } ?: "a4",
            customWidthMm = min(w, h), customHeightMm = max(w, h))
        val size = normalized.paperMm()
        fun edges(a: Double, b: Double, side: Double): Pair<Double, Double> {
            val x = number(a, 0.0, 45.0, 18.0); val y = number(b, 0.0, 45.0, 18.0)
            val factor = if (x + y > side - 24) (side - 24) / (x + y) else 1.0
            return x * factor to y * factor
        }
        val horizontal = edges(leftMm, rightMm, size.first); val vertical = edges(topMm, bottomMm, size.second)
        return normalized.copy(leftMm = horizontal.first, rightMm = horizontal.second, topMm = vertical.first, bottomMm = vertical.second,
            font = font.takeIf { it in listOf("sans-serif", "serif", "monospace") } ?: "sans-serif",
            bodyPt = number(bodyPt, 8.0, 20.0, 11.0), lineHeight = number(lineHeight, 1.0, 2.5, 1.5),
            paragraphBeforePt = number(paragraphBeforePt, 0.0, 32.0, 0.0), paragraphAfterPt = number(paragraphAfterPt, 0.0, 32.0, 8.0),
            indentEm = number(indentEm, 0.0, 4.0, 0.0), headingPt = six(headingPt, 10.0, 40.0, DEFAULT.headingPt),
            headingBeforePt = six(headingBeforePt, 0.0, 48.0, DEFAULT.headingBeforePt), headingAfterPt = six(headingAfterPt, 0.0, 32.0, DEFAULT.headingAfterPt),
            headingLineHeight = number(headingLineHeight, 1.0, 2.0, 1.25), blockGapPt = number(blockGapPt, 0.0, 32.0, 10.0),
            listGapPt = number(listGapPt, 0.0, 16.0, 3.0), listIndentEm = number(listIndentEm, 1.0, 4.0, 1.8),
            quotePaddingPt = number(quotePaddingPt, 0.0, 20.0, 10.0), quoteRulePt = number(quoteRulePt, 0.0, 6.0, 2.0),
            codePt = number(codePt, 7.0, 16.0, 9.0), codeLineHeight = number(codeLineHeight, 1.0, 2.2, 1.4),
            codePaddingPt = number(codePaddingPt, 0.0, 20.0, 8.0), tablePt = number(tablePt, 7.0, 16.0, 10.0),
            tablePaddingPt = number(tablePaddingPt, 1.0, 16.0, 5.0), imageWidth = imageWidth.coerceIn(30, 100),
            imageHeight = imageHeight.coerceIn(20, 100), mathScale = mathScale.coerceIn(80, 180),
            colorMode = colorMode.takeIf { it in listOf("paper", "reading", "mono") } ?: "paper",
            headingPageBreak = headingPageBreak.takeIf { it in listOf("none", "h1", "h2") } ?: "none", widowLines = widowLines.coerceIn(1, 5))
    }

    /** Supply renderer colors and math metrics; physical CSS below remains authoritative. */
    fun readingStyle(reading: MarkdownStyle): MarkdownStyle {
        val p = clamped()
        val colors = if (p.colorMode == "reading") MarkdownStyle.DEFAULT.copy(theme = reading.theme, foreground = reading.foreground,
            background = reading.background, headingColor = reading.headingColor, accent = reading.accent, codeBackground = reading.codeBackground,
            quoteBackground = reading.quoteBackground, borderColor = reading.borderColor)
        else MarkdownStyle.DEFAULT.copy(theme = "light", accent = if (p.colorMode == "mono") "#202124" else null)
        return colors.copy(font = p.font, fontSize = (p.bodyPt * 4 / 3).roundToInt(), lineHeight = p.lineHeight,
            mathScale = p.mathScale, codeSize = (p.codePt * 4 / 3).roundToInt(), codeWrap = p.codeWrap, diagramFit = true)
    }

    fun presetId(): String? = PRESETS.entries.firstOrNull { it.value == clamped() }?.key

    fun toJson() = JSONObject().apply {
        put("paper", paper); put("landscape", landscape); put("customWidthMm", customWidthMm); put("customHeightMm", customHeightMm)
        put("topMm", topMm); put("rightMm", rightMm); put("bottomMm", bottomMm); put("leftMm", leftMm)
        put("font", font); put("bodyPt", bodyPt); put("lineHeight", lineHeight); put("paragraphBeforePt", paragraphBeforePt); put("paragraphAfterPt", paragraphAfterPt)
        put("indentEm", indentEm); put("justified", justified); put("indentAfterHeading", indentAfterHeading)
        put("headingPt", JSONArray(headingPt)); put("headingBeforePt", JSONArray(headingBeforePt)); put("headingAfterPt", JSONArray(headingAfterPt))
        put("headingLineHeight", headingLineHeight); put("headingBold", headingBold); put("headingRules", headingRules)
        put("blockGapPt", blockGapPt); put("listGapPt", listGapPt); put("listIndentEm", listIndentEm)
        put("quotePaddingPt", quotePaddingPt); put("quoteRulePt", quoteRulePt)
        put("codePt", codePt); put("codeLineHeight", codeLineHeight); put("codePaddingPt", codePaddingPt); put("codeWrap", codeWrap)
        put("tablePt", tablePt); put("tablePaddingPt", tablePaddingPt); put("tableBorders", tableBorders); put("tableStripes", tableStripes)
        put("imageWidth", imageWidth); put("imageHeight", imageHeight); put("mathScale", mathScale); put("colorMode", colorMode)
        put("headingPageBreak", headingPageBreak); put("keepHeading", keepHeading); put("keepTableRows", keepTableRows)
        put("repeatTableHeader", repeatTableHeader); put("widowLines", widowLines)
    }

    companion object {
        val DEFAULT = MarkdownPdfStyle()
        val PRESETS: Map<String, MarkdownPdfStyle> = linkedMapOf("standard" to DEFAULT,
            "compact" to DEFAULT.copy(topMm = 12.0, rightMm = 12.0, bottomMm = 12.0, leftMm = 12.0, bodyPt = 10.0, lineHeight = 1.3,
                quotePaddingPt = 6.0,
                paragraphAfterPt = 4.0, blockGapPt = 6.0, listGapPt = 1.0, headingPt = listOf(20.0, 17.0, 14.0, 12.0, 11.0, 10.0),
                headingBeforePt = listOf(12.0, 10.0, 8.0, 6.0, 6.0, 4.0), headingAfterPt = List(6) { 4.0 }, codePt = 8.0, codePaddingPt = 5.0, tablePt = 9.0, tablePaddingPt = 3.0),
            "comfortable" to DEFAULT.copy(topMm = 22.0, rightMm = 22.0, bottomMm = 22.0, leftMm = 22.0, bodyPt = 12.0, lineHeight = 1.75,
                paragraphAfterPt = 12.0, blockGapPt = 14.0, listGapPt = 5.0),
            "academic" to DEFAULT.copy(topMm = 25.0, rightMm = 25.0, bottomMm = 25.0, leftMm = 25.0, font = "serif", bodyPt = 12.0,
                lineHeight = 1.6, paragraphAfterPt = 6.0, indentEm = 2.0, justified = true, headingPt = listOf(22.0, 18.0, 15.0, 13.0, 12.0, 12.0),
                colorMode = "mono", widowLines = 3))

        fun fromJson(o: JSONObject?, legacy: JSONObject? = null): MarkdownPdfStyle {
            if (o == null) return if (legacy == null) DEFAULT else DEFAULT.copy(
                paper = legacy.optString("paper", "a4"), landscape = legacy.optBoolean("landscape", false),
                topMm = legacy.optDouble("printMargin", 18.0), rightMm = legacy.optDouble("printMargin", 18.0),
                bottomMm = legacy.optDouble("printMargin", 18.0), leftMm = legacy.optDouble("printMargin", 18.0),
                colorMode = if (legacy.optBoolean("printColors", false)) "reading" else "paper").clamped()
            val d = DEFAULT
            fun n(k: String, v: Double) = o.optDouble(k, v)
            fun b(k: String, v: Boolean) = o.optBoolean(k, v)
            fun six(k: String, v: List<Double>) = List(6) { o.optJSONArray(k)?.optDouble(it, v[it]) ?: v[it] }
            return MarkdownPdfStyle(paper = o.optString("paper", d.paper), landscape = b("landscape", d.landscape),
                customWidthMm = n("customWidthMm", d.customWidthMm), customHeightMm = n("customHeightMm", d.customHeightMm),
                topMm = n("topMm", d.topMm), rightMm = n("rightMm", d.rightMm), bottomMm = n("bottomMm", d.bottomMm), leftMm = n("leftMm", d.leftMm),
                font = o.optString("font", d.font), bodyPt = n("bodyPt", d.bodyPt), lineHeight = n("lineHeight", d.lineHeight),
                paragraphBeforePt = n("paragraphBeforePt", d.paragraphBeforePt), paragraphAfterPt = n("paragraphAfterPt", d.paragraphAfterPt),
                indentEm = n("indentEm", d.indentEm), justified = b("justified", d.justified), indentAfterHeading = b("indentAfterHeading", d.indentAfterHeading),
                headingPt = six("headingPt", d.headingPt), headingBeforePt = six("headingBeforePt", d.headingBeforePt), headingAfterPt = six("headingAfterPt", d.headingAfterPt),
                headingLineHeight = n("headingLineHeight", d.headingLineHeight), headingBold = b("headingBold", d.headingBold), headingRules = b("headingRules", d.headingRules),
                blockGapPt = n("blockGapPt", d.blockGapPt), listGapPt = n("listGapPt", d.listGapPt), listIndentEm = n("listIndentEm", d.listIndentEm),
                quotePaddingPt = n("quotePaddingPt", d.quotePaddingPt), quoteRulePt = n("quoteRulePt", d.quoteRulePt),
                codePt = n("codePt", d.codePt), codeLineHeight = n("codeLineHeight", d.codeLineHeight), codePaddingPt = n("codePaddingPt", d.codePaddingPt), codeWrap = b("codeWrap", d.codeWrap),
                tablePt = n("tablePt", d.tablePt), tablePaddingPt = n("tablePaddingPt", d.tablePaddingPt), tableBorders = b("tableBorders", d.tableBorders), tableStripes = b("tableStripes", d.tableStripes),
                imageWidth = o.optInt("imageWidth", d.imageWidth), imageHeight = o.optInt("imageHeight", d.imageHeight), mathScale = o.optInt("mathScale", d.mathScale),
                colorMode = o.optString("colorMode", d.colorMode), headingPageBreak = o.optString("headingPageBreak", d.headingPageBreak),
                keepHeading = b("keepHeading", d.keepHeading), keepTableRows = b("keepTableRows", d.keepTableRows), repeatTableHeader = b("repeatTableHeader", d.repeatTableHeader),
                widowLines = o.optInt("widowLines", d.widowLines)).clamped()
        }
    }
}
