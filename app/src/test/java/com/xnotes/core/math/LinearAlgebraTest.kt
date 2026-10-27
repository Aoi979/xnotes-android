package com.xnotes.core.math

import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.OnCanvas
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.*
import org.junit.Test

class LinearAlgebraTest {
    private fun stroke() = Stroke(Tool.PEN, ToolConfig(baseWidth = 4.0), listOf(Sample(10.0, 20.0, 0.7, 0.0), Sample(30.0, 40.0, 0.8, 1.0)), straight = true)

    @Test fun positiveQuarterTurnMovesRightwardPointUpOnScreen() {
        val origin = Pt(100.0, 200.0)
        val result = Matrix2.ROTATE.onCanvas(origin).apply(Pt(130.0, 200.0))
        assertEquals(100.0, result.x, 1e-9)
        assertEquals(170.0, result.y, 1e-9)
        assertEquals(origin, Matrix2.ROTATE.onCanvas(origin).apply(origin))
    }

    @Test fun shearAgreesWithMathematicalCoordinatesFarFromCanvasOrigin() {
        val origin = Pt(1e9, -1e9)
        val map = Matrix2(2.0, 3.0, -1.0, 4.0)
        val p = map.onCanvas(origin).apply(Pt(origin.x + 5.0, origin.y - 7.0))
        val expected = map.apply(Pt(5.0, 7.0))
        assertEquals(origin.x + expected.x, p.x, 1e-6)
        assertEquals(origin.y - expected.y, p.y, 1e-6)
    }

    @Test fun projectionAndZeroMapKeepInkWidthPressureAndTime() {
        for (map in listOf(Matrix2.PROJECT, Matrix2(0.0, 0.0, 0.0, 0.0))) {
            val ink = stroke()
            val style = ink.config
            LinearInk.apply(listOf(ink), map, Pt.ZERO)
            assertEquals(style, ink.config)
            assertEquals(0.0, ink.yAt(1), 0.0)
            assertEquals(0.8, ink.pAt(1), 1e-6)
            assertEquals(1.0, ink.tAt(1), 1e-6)
            assertTrue(ink.geometry().pointCount > 0)
            assertTrue(ink.geometry().hw(0) > 0.0)
        }
    }

    @Test fun transformUndoRedoRestoresExactInkAndSpatialIndex() {
        val ink = stroke()
        val original = ink.samples.toList()
        val doc = InfiniteDocument().apply { add(ink) }
        val history = History()
        val edit = LinearInk.apply(listOf(ink), Matrix2(2.0, 1.0, 0.0, 3.0), Pt.ZERO)
        doc.itemsChanged(listOf(ink))
        history.push(OnCanvas(doc, edit, listOf(ink)))
        val changed = ink.samples.toList()
        assertNotEquals(original, changed)
        assertTrue(doc.itemsIn(ink.bounds()).contains(ink))
        history.undo()
        assertEquals(original, ink.samples.toList())
        assertEquals(4.0, ink.config.baseWidth, 0.0)
        assertTrue(doc.itemsIn(ink.bounds()).contains(ink))
        history.redo()
        assertEquals(changed, ink.samples.toList())
        assertTrue(doc.itemsIn(ink.bounds()).contains(ink))
    }

    @Test fun singularShapeProjectionIsFiniteAndUndoRestoresKind() {
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 20.0), Pt(50.0, 60.0), Rgba(20, 20, 20))
        val before = shape.bounds()
        val edit = LinearInk.apply(listOf(shape), Matrix2.PROJECT, Pt.ZERO)
        assertTrue(shape.bounds().w.isFinite())
        assertEquals(0.0, shape.start.y, 1e-9)
        assertEquals(0.0, shape.end.y, 1e-9)
        edit.undo()
        assertEquals(before, shape.bounds())
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
    }

    @Test fun invalidMatrixCannotPartiallyMutateSelection() {
        val ink = stroke()
        val original = ink.samples.toList()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 21.0)) {
            assertThrows(IllegalArgumentException::class.java) { LinearInk.apply(listOf(ink), Matrix2(bad, 0.0, 0.0, 1.0), Pt.ZERO) }
            assertEquals(original, ink.samples.toList())
        }
        ink.locked = true
        assertFalse(LinearInk.supports(listOf(ink)))
    }
}
