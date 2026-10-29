package com.xnotes.core.cute

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

class CuteWorkbenchTest {
    @Test fun mmaDerivedCopyCaptionsDoNotClaimUnusedThreadAndValueLayouts() {
        val preset = CutePresets.all.first { it.spec.workspace == CuteWorkspace.COPY && it.spec.hardware.copySource == CuteCopySource.MMA_A }.spec
        val evaluation = preset.copy(hardware = preset.hardware.copy(copyThreads = "unused-threads", copyValues = "unused-values")).evaluate()
        val captions = evaluation.sourceLines()
        assertTrue(captions.contains("make_tiled_copy_A"))
        assertFalse(captions.any { "unused-threads" in it || "unused-values" in it })
        assertTrue(captions.any { it.startsWith("AtomLayoutMNK =") })
    }

    @Test fun everyMmaAtomAndOperandHasAnHonestView() {
        for (atom in CuteAtoms.mma) for (part in listOf("A","B","C")) {
            val e = CuteSpec(workspace = CuteWorkspace.MMA, hardware = CuteHardwareSpec(mmaAtom = atom.id, mmaThreads = "(1,1,1):(0,0,0)", operand = part)).evaluate()
            val g = e.grid()
            assertEquals(atom.descriptor(part),e.hardwareLayout!!.descriptor)
            assertEquals(!atom.descriptor(part),g.spec.tv)
            if (atom.descriptor(part)) assertTrue(CuteDiagram.svg(e).contains("logical coordinates only"))
            else assertTrue(g.cell(0,0).owners.isNotEmpty())
        }
    }
    @Test fun everyCopyAtomShowsElementOwnershipForAllThreeSides() {
        for (atom in CuteAtoms.copy) for (part in listOf("S","D","R")) {
            val e = CuteSpec(workspace = CuteWorkspace.COPY, hardware = CuteHardwareSpec(copyAtom = atom.id,copySource = CuteCopySource.ATOM,copySide = part)).evaluate()
            assertTrue(e.grid().spec.tv)
            assertTrue(e.grid().cell(0,0).owners.isNotEmpty())
            assertEquals(e.spec,CuteDiagram.decode(CuteDiagram.encode(e.spec)))
            val suffix = when (part) { "S" -> "Src"; "D" -> "Dst"; else -> "Ref" }
            assertTrue(e.sourceLines().contains("Copy_Atom::ValLayout$suffix"))
        }
    }
    @Test fun advancedSourcesAndPresetsSurviveMetadataRoundTrip() {
        val extra = listOf(CuteSpec(workspace = CuteWorkspace.COPY,hardware = CuteHardwareSpec(retile = true)),CuteSpec(swizzle = CuteSwizzle(2,3,-2,9)))
        (CutePresets.all.map { it.spec } + extra).forEach {
            assertEquals(it,CuteDiagram.decode(CuteDiagram.encode(it)))
            assertTrue(it.cpp().isNotBlank())
        }
    }
    @Test fun versionOneAssetsRemainEditable() {
        val spec = CuteSpec(a = "(4,2):(1,4)",tv = false)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(1); it.writeUTF(spec.a); it.writeUTF(spec.operation.name); it.writeUTF(spec.b)
            it.writeBoolean(spec.tv); it.writeInt(spec.tileM); it.writeInt(spec.tileN); it.writeBoolean(spec.rowMajor)
            it.writeBoolean(spec.showInput); it.writeInt(spec.rowStart); it.writeInt(spec.columnStart)
        }
        assertEquals(spec,CuteDiagram.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())))
    }
    @Test fun swizzleInputToggleAndGridUseActualComposedOffsets() {
        val spec = CuteSpec(a = "(8,64):(64,1)",tv = false,swizzle = CuteSwizzle())
        val e = spec.evaluate()
        assertEquals(72L,e.grid().cell(1,0).offset)
        assertEquals(64L,spec.copy(showInput = true).evaluate().grid().cell(1,0).offset)
        assertEquals(0L,e.grid().holes)
        assertTrue(spec.cpp().contains("composition(Swizzle<3,3,3>{}, Int<0>{}, r)"))
    }
    @Test fun swizzleConstraintsAndInvolution() {
        for (s in listOf(CuteSwizzle(),CuteSwizzle(2,3,-2),CuteSwizzle(0,0,0),CuteSwizzle(3,4,3))) {
            s.validate()
            for (i in 0L..4095L) assertEquals(i,s.apply(s.apply(i)))
        }
        listOf(CuteSwizzle(3,3,2),CuteSwizzle(-1),CuteSwizzle(base = -1),CuteSwizzle(shift = Int.MIN_VALUE),CuteSwizzle(offset = -1)).forEach {
            assertThrows(CuteException::class.java) { it.validate() }
        }
    }
    @Test fun descriptorCopyAndIncompatibleCopySizesAreRejected() {
        assertThrows(CuteException::class.java) {
            CuteHardwareSpec(mmaAtom = "sm90_gmma_ss",copySource = CuteCopySource.MMA_A).copy()
        }
        assertThrows(CuteException::class.java) {
            CuteTiledCopy.make(CuteAtoms.copy("ldsm_n4"),CuteParser.layout("(4,4):(1,4)"),CuteParser.layout("(1,8):(0,1)"))
        }
    }
    @Test fun tmaCoordinatesBytesBanksAndOutOfBounds() {
        val t = CuteTmaSpec(origin = "(96,62)",sharedBase = 128).analyze()
        assertEquals(1024L,t.allocationBytes); assertEquals(1024L,t.transferBytes)
        assertEquals(96L * 2 + 62L * 256,t.element(0).globalByte)
        assertEquals(16L,t.element(0).sharedByte); assertEquals(4,t.element(0).bank)
        assertTrue(t.element(63).outOfBounds); assertNull(t.element(63).globalByte)
        assertEquals(listOf(96L,63L),t.element(64).coordinate)
        assertTrue(t.element(128).outOfBounds)
        val addresses = (0 until t.layout.size).map { t.element(it).sharedByte }
        assertEquals(512,addresses.toSet().size)
        assertTrue(addresses.all { it in 0 until t.allocationBytes })
    }
    @Test fun tmaSmemMatchesNativeCuteComposedByteSwizzles() {
        val lines = javaClass.getResourceAsStream("/cute/advanced.tsv")!!.bufferedReader().readLines()
        for ((span,base,bits,inner) in listOf(listOf(64,128,2,32),listOf(32,256,1,16))) {
            val row = lines.map { it.split('\t') }.first { it.first() == "swizzle" && it[1] == "$bits,4,3,$base" }
            val t = CuteTmaSpec(box = "($inner,8)",swizzleBytes = span,sharedBase = base.toLong()).analyze()
            row.last().split(',').map { it.toLong() }.forEachIndexed { i,v -> assertEquals(v-base,t.element(i.toLong()).sharedByte) }
        }
    }
    @Test fun tmaSamplingPaddingAndFiveDimensions() {
        val t = CuteTmaSpec(globalShape = "(64,12,4,2,2)",globalStrides = "(128,1536,6144,12288)",box = "(16,5,2,1,1)",origin = "(0,1,0,0,0)",steps = "(8,2,1,1,1)",swizzleBytes = 64).analyze()
        assertEquals(listOf(16L,3L,2L,1L,1L),t.sampleShape)
        assertEquals(listOf(0L,5L,0L,0L,0L),t.element(32).coordinate)
        assertEquals(192L,t.transferBytes); assertEquals(384L,t.allocationBytes)
        assertEquals(64L,t.rowPitchBytes)
        assertEquals(listOf(0L,1L,1L,0L,0L),t.element(48).coordinate)
    }
    @Test fun tmaRejectsInvalidDescriptorsAndSupportsOneDimension() {
        val base = CuteTmaSpec()
        listOf(base.copy(globalStrides = "(130)"),base.copy(globalStrides = "(128)"),base.copy(globalBase = 16),base.copy(sharedBase = 16),base.copy(box = "(65,8)"),base.copy(box = "(128,8)"),base.copy(steps = "(1,9)"),base.copy(origin = "(0)"),base.copy(globalShape = "(0,64)")).forEach {
            assertThrows(CuteException::class.java) { it.analyze() }
        }
        val t = CuteTmaSpec(globalShape = "(128)",globalStrides = "()",box = "(16)",origin = "(-8)",steps = "(1)",swizzleBytes = 0).analyze()
        assertTrue(t.element(0).outOfBounds); assertEquals(0L,t.element(8).globalByte)
        assertEquals(32L,t.allocationBytes)
    }
}
