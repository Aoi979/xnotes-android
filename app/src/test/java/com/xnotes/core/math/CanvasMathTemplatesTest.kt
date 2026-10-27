package com.xnotes.core.math

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.Head
import com.xnotes.format.CanvasCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CanvasMathTemplatesTest {
    private val ink = Rgba(30, 30, 30)
    private val measurer = FakeTextMeasurer()

    @Test fun smallNonZeroValuesAreNotLabelledAsZero() {
        assertEquals(1e-12, CanvasMathTemplates.number(1e-12).toDouble(), 1e-20)
        assertEquals(-0.0007, CanvasMathTemplates.number(-0.0007).toDouble(), 1e-12)
        assertEquals("0", CanvasMathTemplates.number(-0.0))
        assertEquals("1", CanvasMathTemplates.number(1.0))
        assertEquals("20", CanvasMathTemplates.number(20.0))
    }

    @Test fun previewAndPlacementDoNotShareMutableTemplateGeometry() {
        val template = CanvasMathTemplates.matrix(MatrixFrameSpec(), ink)
        val preview = template.materialize(measurer)
        val original = preview.first().bounds()
        val placed = template.materialize(measurer)
        placed.first().translate(500.0, 900.0)
        assertEquals(original, preview.first().bounds())
        assertEquals(original, template.materialize(measurer).first().bounds())
    }

    @Test fun guidesRemainReadableWithBrightInkOnLightPaper() {
        val green = Rgba(0, 230, 118)
        assertNotEquals(green, CanvasMathTemplates.readableInk(green, Rgba(255, 255, 255)))
        assertEquals(green, CanvasMathTemplates.readableInk(green, Rgba(0, 0, 0)))
        assertTrue(CanvasMathTemplates.readableInk(Rgba(10, 10, 10), Rgba(0, 0, 0)).luminance() > 0.5)
    }

    @Test fun everyBracketAndExtremeMatrixSizeLeavesWritableBlankCells() {
        for (bracket in MatrixBracket.entries) for (size in listOf(1, 8)) {
            val drawing = CanvasMathTemplates.matrix(MatrixFrameSpec(size, size, bracket, augmented = true), ink)
            assertTrue(drawing.labels.isEmpty())
            assertEquals(2 + (size - 1) * 2, drawing.shapes.size)
            drawing.shapes.forEach { shape ->
                assertTrue(shape.paths().isNotEmpty())
                assertTrue(shape.bounds().w.isFinite() && shape.bounds().h.isFinite())
            }
        }
    }

    @Test fun vectorSumEndsAtSumAndUsesTranslatedParallelogramEdges() {
        val drawing = CanvasMathTemplates.vectors(ink, Pt(2.0, 1.0), Pt(-1.0, 3.0))
        val arrows = drawing.shapes.filter { it.headEnd == Head.OPEN }
        val total = Pt(40.0, -160.0)
        assertTrue(arrows.any { it.start == Pt.ZERO && it.end == total })
        assertTrue(arrows.any { it.start == Pt(80.0, -40.0) && it.end == total && it.dashed })
        assertTrue(arrows.any { it.start == Pt(-40.0, -120.0) && it.end == total && it.dashed })
    }

    @Test fun basisArrowsAreExactlyTheMatrixColumns() {
        val drawing = CanvasMathTemplates.linearMap(Matrix2(2.0, -1.0, 3.0, 0.0), ink)
        val arrows = drawing.shapes.filter { it.headEnd == Head.OPEN && it.start == Pt.ZERO }
        assertTrue(arrows.any { it.end == Pt(80.0, -120.0) })
        assertTrue(arrows.any { kotlin.math.abs(it.end.x + 40.0) < 1e-9 && kotlin.math.abs(it.end.y) < 1e-9 })
    }

    @Test fun templatesRoundTripAsEditableShapesAndUndoAsOneInsertion() {
        val diagrams = listOf(
            CanvasMathTemplates.matrix(MatrixFrameSpec(3, 4, MatrixBracket.ROUND, true), ink),
            CanvasMathTemplates.vectors(ink),
            CanvasMathTemplates.linearMap(Matrix2.SHEAR, ink),
            CanvasMathTemplates.linearMap(Matrix2.PROJECT, ink),
        )
        val codec = CanvasCodec(FakeImageCodec(), measurer)
        for (diagram in diagrams) {
            val items = diagram.materialize(measurer)
            val doc = InfiniteDocument().apply { addAll(items) }
            val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
            val restored = codec.read(ByteArrayInputStream(bytes))
            assertEquals(items.size, restored.itemCount)
            items.zip(restored.items).forEach { (before, after) ->
                assertTrue(after is ShapeItem)
                // A saved axis-aligned four-point polygon may be recovered as a rectangle.
                after as ShapeItem
                assertEquals(before.headEnd, after.headEnd)
                assertEquals(before.bounds().x, after.bounds().x, 1e-5)
                assertEquals(before.bounds().y, after.bounds().y, 1e-5)
                assertEquals(before.bounds().w, after.bounds().w, 1e-5)
                assertEquals(before.bounds().h, after.bounds().h, 1e-5)
            }
            val history = History().apply { push(AddCanvasItems(doc, items)) }
            history.undo()
            assertTrue(doc.isEmpty)
            history.redo()
            assertEquals(items.size, doc.itemCount)
        }
    }
}
