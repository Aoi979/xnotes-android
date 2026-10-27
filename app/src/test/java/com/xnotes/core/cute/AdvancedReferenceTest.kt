package com.xnotes.core.cute

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AdvancedReferenceTest(private val kind: String, private val x: String, private val y: String,
                            private val z: String, private val w: String, private val expected: String, private val offsets: String) {
    @Test fun matchesNativeCuteAcrossEntireDomain() {
        val expectedOffsets = offsets.split(',').map { it.toLong() }
        if (kind == "swizzle") {
            val p = x.split(',').map { it.toLong() }
            val s = CuteSwizzle(p[0].toInt(),p[1].toInt(),p[2].toInt(),p[3]).also { it.validate() }
            val l = CuteParser.layout(y)
            expectedOffsets.forEachIndexed { i,v -> assertEquals(v,s.map(l.index(i.toLong()))) }
            return
        }
        val result = when (kind) {
            "major" -> CuteLayout.compact(CuteParser.intTree(x),y == "Right")
            "mma_copy" -> CuteTiledCopy.fromMma(CuteAtoms.copy(x),CuteTiledMma(CuteAtoms.mma("sm80_f16"),CuteParser.layout(z),CuteParser.operand("(_,_,_)")),y).layout(w).layout
            "retile" -> CuteTiledCopy.make(CuteAtoms.copy(x),CuteParser.layout(y),CuteParser.layout(z)).retile(CuteParser.layout(w))
            else -> error(kind)
        }
        assertEquals(expected.replace(" ",""),result.toString())
        assertEquals(expectedOffsets.size.toLong(),result.size)
        expectedOffsets.forEachIndexed { i,v -> assertEquals("$kind offset $i",v,result.index(i.toLong())) }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}/{2}/{4}")
        fun cases(): List<Array<String>> = AdvancedReferenceTest::class.java.getResourceAsStream("/cute/advanced.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t').toTypedArray() }.toList()
        }
    }
}
