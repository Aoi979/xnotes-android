package com.xnotes.core.cute

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HardwareReferenceTest(private val kind: String, private val atom: String, private val x: String,
                            private val y: String, private val part: String, private val expected: String, private val offsets: String) {
    @Test fun nativeCuteStructureAndFullMapping() {
        val result = if (kind == "mma") CuteTiledMma(CuteAtoms.mma(atom), CuteParser.layout(x), CuteParser.operand(y)).layout(part)
        else CuteTiledCopy.make(CuteAtoms.copy(atom), CuteParser.layout(x), CuteParser.layout(y)).layout(part)
        assertEquals(expected.replace(" ",""),result.layout.toString())
        val values = offsets.split(',').map { it.toLong() }
        assertEquals(values.size.toLong(),result.layout.size)
        values.forEachIndexed { i, value -> assertEquals("offset $i",value,result.layout.index(i.toLong())) }
        for (t in 0 until result.layout.mode(0).size.toInt()) {
            result.partition(t).forEachIndexed { v, value -> assertEquals(values[t + result.layout.mode(0).size.toInt() * v],value) }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}/{4}/{2}/{3}")
        fun cases(): List<Array<String>> = HardwareReferenceTest::class.java.getResourceAsStream("/cute/hardware.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t').toTypedArray() }.toList()
        }
    }
}
