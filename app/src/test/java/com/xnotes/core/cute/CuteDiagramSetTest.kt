package com.xnotes.core.cute

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.format.CanvasCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CuteDiagramSetTest {
    private val mma = CuteSpec(workspace = CuteWorkspace.MMA,
        hardware = CuteHardwareSpec(mmaThreads = "(1,1,1):(0,0,0)"))

    @Test fun gemmBTransposesPresentationWithoutChangingNativeOwnersOrOffsets() {
        val b = CuteDiagramSet.create(mma).single { it.asset.spec.hardware.operand == "B" }.asset
        val original = b.spec.copy(transposeDiagram = false).evaluate().grid()
        val shown = b.spec.evaluate().grid()
        assertEquals(8, original.rows)
        assertEquals(16, original.columns)
        assertEquals(original.columns, shown.rows)
        assertEquals(original.rows, shown.columns)
        for (k in 0 until shown.rows) for (n in 0 until shown.columns) {
            val before = original.cell(n, k)
            val after = shown.cell(k, n)
            assertEquals(before.offset, after.offset)
            assertEquals(before.index, after.index)
            assertEquals(before.owners, after.owners)
            assertEquals(original.labels(before), shown.labels(after))
            assertEquals(original.color(before), shown.color(after))
        }
    }

    @Test fun gemmAlignsMatrixAxesAndKeepsAllSvgCaptionsSeparated() {
        val panels = CuteDiagramSet.create(mma.copy(rowStart = 7, columnStart = 3, showInput = true))
        val (a, b, c) = panels
        assertEquals(listOf("A", "B", "C"), panels.map { it.asset.spec.hardware.operand })
        assertTrue(a.rect.right < c.rect.left)
        assertTrue(b.rect.bottom < minOf(a.rect.top, c.rect.top))
        assertEquals(a.gridRect.top, c.gridRect.top, 0.0)
        assertEquals(a.gridRect.h, c.gridRect.h, 0.0)
        assertEquals(b.gridRect.left, c.gridRect.left, 0.0)
        assertEquals(b.gridRect.w, c.gridRect.w, 0.0)
        assertEquals(a.gridRect.w, b.gridRect.h, 0.0)
        for (i in panels.indices) for (j in i + 1 until panels.size) assertFalse(panels[i].rect.intersects(panels[j].rect))
        panels.forEach {
            assertEquals(0, it.asset.spec.rowStart)
            assertEquals(0, it.asset.spec.columnStart)
        }
    }

    @Test fun descriptorOperandsKeepLogicalCoordinatesWhenPresentedAsGemmB() {
        val spec = mma.copy(hardware = mma.hardware.copy(mmaAtom = "sm90_gmma_ss"))
        val b = CuteDiagramSet.create(spec).single { it.asset.spec.hardware.operand == "B" }.asset
        val native = b.spec.copy(transposeDiagram = false).evaluate().grid()
        val shown = b.spec.evaluate().grid()
        assertFalse(shown.spec.tv)
        assertTrue(b.svg.contains("logical coordinates only"))
        for (k in shown.rowStart until shown.rowEnd) for (n in shown.columnStart until shown.columnEnd) {
            assertEquals(native.cell(n, k).offset, shown.cell(k, n).offset)
            assertTrue(shown.cell(k, n).owners.isEmpty())
        }
    }

    @Test fun allCopyPresetsInsertSourceThenDestinationAtOneGridScale() {
        for (preset in CutePresets.all.filter { it.spec.workspace == CuteWorkspace.COPY }) {
            val (s, d) = CuteDiagramSet.create(preset.spec)
            assertEquals("S", s.asset.spec.hardware.copySide)
            assertEquals("D", d.asset.spec.hardware.copySide)
            assertTrue(preset.name, s.rect.right < d.rect.left)
            assertEquals(s.gridRect.top, d.gridRect.top, 0.0)
            assertFalse(s.asset.spec.transposeDiagram)
            assertFalse(d.asset.spec.transposeDiagram)
        }
    }

    @Test fun insertedSetSavesEachEditableAssetAndIsOneUndoStep() {
        val dir = Files.createTempDirectory("cute-set").toFile()
        try {
            val placements = CuteDiagramSet.create(mma)
            val items = placements.mapIndexed { i, p ->
                val f = dir.resolve("$i.svg").apply { writeText(p.asset.svg) }
                ImageItem(ImageData(f, p.asset.width, p.asset.height), p.rect)
            }
            val document = InfiniteDocument().apply { addAll(items) }
            val history = History().apply { push(AddCanvasItems(document, items)) }
            val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
            val bytes = ByteArrayOutputStream().also { codec.write(document, it) }.toByteArray()
            val loaded = codec.read(ByteArrayInputStream(bytes), imageDir = dir)
            loaded.items.forEachIndexed { i, item ->
                val image = item as ImageItem
                assertEquals(placements[i].asset.spec, CuteDiagram.read(image.image.file))
                assertEquals(items[i].rect, image.rect)
            }
            assertTrue(CuteDiagram.read((loaded.items[1] as ImageItem).image.file)!!.transposeDiagram)
            history.undo()
            assertTrue(document.isEmpty)
            assertFalse(history.canUndo)
            history.redo()
            assertEquals(items, document.items)
            // Ordinary single diagrams retain the metadata version understood by existing files.
            val encoded = Base64.getDecoder().decode(CuteDiagram.encode(mma))
            assertEquals(2, DataInputStream(ByteArrayInputStream(encoded)).readInt())
        } finally {
            dir.deleteRecursively()
        }
    }
}
