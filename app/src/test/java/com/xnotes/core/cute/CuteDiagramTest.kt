package com.xnotes.core.cute

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.format.CanvasCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class CuteDiagramTest {
    @Test fun nvidiaMmaCPositionsAndColors() {
        val g = CuteSpec().evaluate().grid()
        assertEquals(CuteOwner(0, 0, 0), g.cell(0, 0).owners.single())
        assertEquals(0, g.cell(0, 1).owners.single().thread)
        assertEquals(1, g.cell(0, 1).owners.single().value)
        assertEquals(1, g.cell(0, 2).owners.single().thread)
        assertEquals(2, g.cell(8, 0).owners.single().value)
        assertEquals(31, g.cell(15, 7).owners.single().thread)
        assertEquals(3, g.cell(15, 7).owners.single().value)
        assertEquals("#AFAFFF", g.color(g.cell(0, 0)))
        assertEquals("#AFFFAF", g.color(g.cell(0, 2)))
        assertEquals(0, g.aliases); assertEquals(0L, g.holes)
    }

    @Test fun allPresetsAreEvaluableAndVectorExportable() {
        CutePresets.all.forEach {
            val e = it.spec.evaluate()
            assertTrue(it.name, CuteDiagram.svg(e).contains("<metadata"))
            assertNotNull(e.grid())
        }
    }

    @Test fun aliasesAndEmptyCellsRemainVisible() {
        val grid = CuteSpec(a = "(4,2):(0,1)", tileM = 2, tileN = 4).evaluate().grid()
        assertEquals(6, grid.aliases); assertEquals(6L, grid.holes)
        assertEquals(4, grid.cell(0, 0).owners.size)
        assertEquals(listOf("T0", "V0+"), grid.labels(grid.cell(0, 0)))
        assertTrue(grid.cell(1, 3).owners.isEmpty())
    }

    @Test fun tvNeverWrapsOutOfTileOffsets() {
        val spec = CuteSpec(tileM = 4, tileN = 4)
        assertEquals("tv_outside", assertThrows(CuteException::class.java) { spec.evaluate().grid() }.code)
    }

    @Test fun largeLayoutsUseExplicitBoundedWindows() {
        val e = CuteSpec("(256,256):(1,256)", tv = false, rowStart = 224, columnStart = 224).evaluate()
        val grid = e.grid()
        assertEquals(256, grid.rowEnd); assertEquals(256, grid.columnEnd)
        assertEquals(65535L, grid.cell(255, 255).offset)
        val svg = CuteDiagram.svg(e)
        assertTrue(svg.contains("Window: rows 224..255"))
        assertTrue(svg.length < 400_000)
    }

    @Test fun savedCanvasPreservesEditableSourceAndSvg() {
        val dir = Files.createTempDirectory("cute-roundtrip").toFile()
        try {
            val spec = CutePresets.all[5].spec.copy(rowStart = 1, showInput = true)
            val file = dir.resolve("diagram.svg").apply { writeText(CuteDiagram.svg(spec.evaluate())) }
            val doc = InfiniteDocument().apply { add(ImageItem(ImageData(file, 640, 600), Rect(10.0, 20.0, 320.0, 300.0))) }
            val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
            val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
            val loaded = codec.read(ByteArrayInputStream(bytes), imageDir = dir)
            val image = loaded.items.single() as ImageItem
            assertEquals(spec, CuteDiagram.read(image.image.file))
            assertEquals(file.readText(), image.image.file.readText())
            assertEquals(doc.items.single().bounds(), image.bounds())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptOrUnrelatedMetadataIsNotEditable() {
        assertNull(CuteDiagram.decode("!not-base64!"))
        assertNull(CuteDiagram.decode("A".repeat(12001)))
        val spec = CuteSpec(a = "(4,8):(1,4)\n", b = "tile(2:1,2:1)", operation = CuteOperation.ZIPPED_DIVIDE, tv = false)
        assertEquals(spec, CuteDiagram.decode(CuteDiagram.encode(spec)))
    }

    @Test fun customCodomainDecoderDoesNotChangeTvDomainCoordinates() {
        val e = CuteSpec(a = "(4,2):(2,1)", tileM = 2, tileN = 4, rowMajor = true).evaluate()
        assertEquals(CuteOwner(0,1,4), e.grid().cell(0,1).owners.single())
        assertEquals(CuteOwner(1,0,1), e.grid().cell(0,2).owners.single())
        assertEquals("(0,1)",e.shown.tvCoordinate(0,1).toString())
        assertTrue(CuteDiagram.decode(CuteDiagram.encode(e.spec))!!.rowMajor)
    }

    @Test fun rejectsInvalidInputAndNonrepresentableComposition() {
        listOf("tile4", "tile_", "(2,3):(1)", "(0,3):(1,2)", "4:-1", "(256,257):(1,256)", "(2,3):(1,2)garbage", "(".repeat(10)+"1"+")".repeat(10)).forEach {
            assertThrows(it, CuteException::class.java) { CuteParser.layout(it) }
        }
        assertThrows(CuteException::class.java) { CuteOperation.COMPOSITION.apply(CuteParser.layout("(6,2):(8,2)"), "4:4") }
        assertThrows(CuteException::class.java) { CuteOperation.COMPLEMENT.apply(CuteParser.layout("(4,4):(1,1)"), "32") }
    }

    @Test fun maximumSizedDomainAllowsExtendedCompositionIntermediate() {
        val a = CuteParser.layout("(65536,1):(1,0)")
        val result = CuteOperation.COMPOSITION.apply(a, "65536:1")
        assertEquals("65536:1", result.toString())
        for (i in 0 until a.size) assertEquals(a.index(i), result.index(i))
    }

    @Test fun printedStaticTuplesAndNestedTilesAreParsed() {
        assertEquals("((4,8),2):((8,1),32)", CuteParser.layout("((_4, _8), _2):((_8,_1),_32)").toString())
        assertEquals(CuteParser.operand("(4,2)"), CuteParser.operand("tile(4,2)"))
        assertEquals("8:1", CuteParser.layout("8").toString())
        assertEquals("(4,2):(1,4)", CuteParser.layout("(4,2)").toString())
    }
}
