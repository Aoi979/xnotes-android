package com.xnotes.core.math

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.model.deepCopy
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow

enum class MatrixBracket { SQUARE, ROUND, BARS }

data class MatrixFrameSpec(
    val rows: Int = 3,
    val columns: Int = 3,
    val bracket: MatrixBracket = MatrixBracket.SQUARE,
    val augmented: Boolean = false,
    val guides: Boolean = true,
)

data class DiagramLabel(val text: String, val at: Pt, val color: Rgba, val size: Double = 16.0)

/** All output is ordinary editable geometry, so existing codecs, erasers and PDF export work. */
class MathDiagram(val shapes: List<ShapeItem>, val labels: List<DiagramLabel> = emptyList()) {
    fun materialize(measurer: TextMeasurer): List<ShapeItem> = shapes.map { it.deepCopy(measurer) as ShapeItem } + labels.mapNotNull { label ->
        val text = ShapeLabel(label.text, TextStyle(label.color, label.size, FontFace.SANS), measurer)
        text.asGlyphs(Rect(label.at.x, label.at.y, text.naturalWidth + 1.0, text.lineHeight), { it }, 0.0)
    }
}

object CanvasMathTemplates {
    private val BLUE = Rgba(56, 120, 230)
    private val ORANGE = Rgba(224, 128, 35)
    private val PURPLE = Rgba(157, 87, 219)

    /** Bright pen swatches need a neutral guide colour on light paper. */
    fun readableInk(candidate: Rgba, paper: Rgba): Rgba {
        fun luminance(color: Rgba): Double {
            fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b)
        }
        val foreground = luminance(candidate)
        val background = luminance(paper)
        val contrast = (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05)
        return if (contrast >= 3.0) candidate.copy(a = 255)
        else if (background > 0.4) Rgba(35, 39, 47) else Rgba(226, 232, 240)
    }

    fun matrix(spec: MatrixFrameSpec, ink: Rgba): MathDiagram {
        require(spec.rows in 1..8 && spec.columns in 1..8)
        val w = spec.columns * 64.0
        val h = spec.rows * 56.0
        val shapes = ArrayList<ShapeItem>()
        if (spec.guides) {
            for (row in 1 until spec.rows) shapes += line(Pt(12.0, row * 56.0), Pt(w + 12.0, row * 56.0), ink.scaleAlpha(0.24), 1.0, dashed = true)
            for (column in 1 until spec.columns) {
                if (spec.augmented && column == spec.columns - 1) continue
                shapes += line(Pt(12.0 + column * 64.0, 0.0), Pt(12.0 + column * 64.0, h), ink.scaleAlpha(0.24), 1.0, dashed = true)
            }
        }
        if (spec.augmented && spec.columns > 1) {
            val x = 12.0 + (spec.columns - 1) * 64.0
            shapes += line(Pt(x, 3.0), Pt(x, h - 3.0), ink, 2.0)
        }
        for (right in listOf(false, true)) {
            val x = if (right) w + 24.0 else 0.0
            val inset = if (right) -10.0 else 10.0
            shapes += when (spec.bracket) {
                MatrixBracket.SQUARE -> path(listOf(FreePath.lines(listOf(Pt(x + inset, 0.0), Pt(x, 0.0), Pt(x, h), Pt(x + inset, h)), false)), ink, 2.5)
                MatrixBracket.BARS -> line(Pt(x, 0.0), Pt(x, h), ink, 2.5)
                MatrixBracket.ROUND -> path(listOf(VectorContour(Pt(x + inset, 0.0), listOf(VectorSeg.Cubic(Pt(x - inset, h * 0.22), Pt(x - inset, h * 0.78), Pt(x + inset, h))), false)), ink, 2.5)
            }
        }
        return MathDiagram(shapes)
    }

    /** A blank plane, or a two-vector construction with a genuine parallelogram sum. */
    fun vectors(ink: Rgba, u: Pt = Pt(2.0, 1.0), v: Pt = Pt(1.0, 2.0), showVectors: Boolean = true, sum: Boolean = true, grid: Boolean = true): MathDiagram {
        require(listOf(u.x, u.y, v.x, v.y).all { it.isFinite() && kotlin.math.abs(it) <= 20.0 })
        val extent = if (showVectors) kotlin.math.ceil(maxOf(4.0, kotlin.math.abs(u.x), kotlin.math.abs(u.y), kotlin.math.abs(v.x), kotlin.math.abs(v.y), if (sum) kotlin.math.abs(u.x + v.x) else 0.0, if (sum) kotlin.math.abs(u.y + v.y) else 0.0)).toInt() else 4
        val shapes = ArrayList<ShapeItem>()
        val labels = ArrayList<DiagramLabel>()
        plane(shapes, labels, ink, extent, grid)
        val step = 40.0
        fun p(value: Pt) = Pt(value.x * step, -value.y * step)
        if (showVectors) {
            if (sum) {
                val total = u + v
                shapes += polygon(listOf(Pt.ZERO, p(u), p(total), p(v)), PURPLE.scaleAlpha(0.35), PURPLE.scaleAlpha(0.10))
                shapes += arrow(p(u), p(total), ORANGE.scaleAlpha(0.65), dashed = true)
                shapes += arrow(p(v), p(total), BLUE.scaleAlpha(0.65), dashed = true)
                shapes += arrow(Pt.ZERO, p(total), PURPLE)
                labels += DiagramLabel("u + v", p(total) + Pt(8.0, -22.0), PURPLE)
            }
            shapes += arrow(Pt.ZERO, p(u), BLUE)
            shapes += arrow(Pt.ZERO, p(v), ORANGE)
            labels += DiagramLabel("u", p(u) + Pt(8.0, 4.0), BLUE)
            labels += DiagramLabel("v", p(v) + Pt(8.0, -22.0), ORANGE)
            labels += DiagramLabel("u = (${number(u.x)}, ${number(u.y)})    v = (${number(v.x)}, ${number(v.y)})", Pt(-extent * step, (extent + 1.0) * step), ink)
        }
        return MathDiagram(shapes, labels)
    }

    /** Before/after lattice, images of the standard basis, and the determinant's signed area. */
    fun linearMap(matrix: Matrix2, ink: Rgba, grid: Boolean = true): MathDiagram {
        require(matrix.valid)
        val shapes = ArrayList<ShapeItem>()
        val labels = ArrayList<DiagramLabel>()
        plane(shapes, labels, ink, 3, grid)
        fun p(x: Double, y: Double): Pt = matrix.apply(Pt(x, y)).let { Pt(it.x * 40.0, -it.y * 40.0) }
        if (grid) for (i in -3..3) {
            shapes += line(p(i.toDouble(), -3.0), p(i.toDouble(), 3.0), BLUE.scaleAlpha(0.35), 1.0)
            shapes += line(p(-3.0, i.toDouble()), p(3.0, i.toDouble()), BLUE.scaleAlpha(0.35), 1.0)
        }
        shapes += polygon(listOf(Pt.ZERO, Pt(40.0, 0.0), Pt(40.0, -40.0), Pt(0.0, -40.0)), ink.scaleAlpha(0.5), ink.scaleAlpha(0.06))
        shapes += polygon(listOf(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0), p(0.0, 1.0)), BLUE, BLUE.scaleAlpha(0.16))
        shapes += arrow(Pt.ZERO, p(1.0, 0.0), BLUE)
        shapes += arrow(Pt.ZERO, p(0.0, 1.0), ORANGE)
        labels += DiagramLabel("A e₁", p(1.0, 0.0) + Pt(8.0, 4.0), BLUE)
        labels += DiagramLabel("A e₂", p(0.0, 1.0) + Pt(8.0, -22.0), ORANGE)
        val bottom = shapes.maxOf { it.bounds().bottom }
        labels += DiagramLabel("A = [${number(matrix.a)}  ${number(matrix.b)}; ${number(matrix.c)}  ${number(matrix.d)}]    det(A) = ${number(matrix.determinant)}", Pt(shapes.minOf { it.bounds().left }, bottom + 28.0), ink)
        return MathDiagram(shapes, labels)
    }

    private fun plane(shapes: MutableList<ShapeItem>, labels: MutableList<DiagramLabel>, ink: Rgba, extent: Int, grid: Boolean) {
        val side = extent * 40.0
        if (grid) for (i in -extent..extent) if (i != 0) {
            shapes += line(Pt(i * 40.0, -side), Pt(i * 40.0, side), ink.scaleAlpha(0.17), 1.0)
            shapes += line(Pt(-side, i * 40.0), Pt(side, i * 40.0), ink.scaleAlpha(0.17), 1.0)
        }
        shapes += arrow(Pt(-side - 12.0, 0.0), Pt(side + 18.0, 0.0), ink, width = 1.8)
        shapes += arrow(Pt(0.0, side + 12.0), Pt(0.0, -side - 18.0), ink, width = 1.8)
        for (i in -extent..extent) if (i != 0) {
            shapes += line(Pt(i * 40.0, -3.0), Pt(i * 40.0, 3.0), ink, 1.3)
            shapes += line(Pt(-3.0, i * 40.0), Pt(3.0, i * 40.0), ink, 1.3)
            if (extent <= 6) {
                labels += DiagramLabel(i.toString(), Pt(i * 40.0 - 5.0, 8.0), ink, 11.0)
                labels += DiagramLabel((-i).toString(), Pt(-24.0, i * 40.0 - 7.0), ink, 11.0)
            }
        }
        labels += DiagramLabel("x", Pt(side + 24.0, -9.0), ink)
        labels += DiagramLabel("y", Pt(8.0, -side - 30.0), ink)
        labels += DiagramLabel("0", Pt(7.0, 7.0), ink, 11.0)
    }

    private fun line(from: Pt, to: Pt, color: Rgba, width: Double = 2.0, dashed: Boolean = false) =
        ShapeItem(ShapeKind.LINE, from, to, color, width, dashed = dashed, dashLength = 5.0, dashGap = 5.0)

    private fun arrow(from: Pt, to: Pt, color: Rgba, width: Double = 2.6, dashed: Boolean = false) =
        line(from, to, color, width, dashed).apply { headEnd = Head.OPEN }

    private fun polygon(points: List<Pt>, color: Rgba, fill: Rgba): ShapeItem = path(listOf(FreePath.lines(points, true)), color, 1.5).apply {
        shape = ShapeKind.POLYGON
        fillRgba = fill
    }

    private fun path(contours: List<VectorContour>, color: Rgba, width: Double): ShapeItem {
        val bounds = FreePath.bounds(contours) ?: Rect(0.0, 0.0, 1.0, 1.0)
        return ShapeItem(ShapeKind.POLYLINE, bounds.topLeft, Pt(bounds.right, bounds.bottom), color, width, path = FreePath.normalize(contours, bounds))
    }

    /** Six significant digits keep a small nonzero determinant visibly distinct from zero. */
    fun number(value: Double): String {
        val decimal = BigDecimal.valueOf(value).round(MathContext(6)).stripTrailingZeros()
        return if (value == 0.0 || kotlin.math.abs(value) in 0.0001..1e6) decimal.toPlainString() else decimal.toString()
    }
}
