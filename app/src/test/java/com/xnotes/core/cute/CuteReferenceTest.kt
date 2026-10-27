package com.xnotes.core.cute

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Goldens are emitted by actual CuTe C++ templates, never by the Kotlin implementation. */
@RunWith(Parameterized::class)
class CuteReferenceTest(private val op: String, private val a: String, private val b: String, private val expected: String, private val offsets: String) {
    @Test fun matchesCuteStructureAndEveryOffset() {
        val operation = CuteOperation.entries.single { it.id == op }
        val result = operation.apply(CuteParser.layout(a), b)
        assertEquals("$op($a, $b): structure", expected, result.toString())
        val addresses = offsets.split(',').map { it.toLong() }
        assertEquals(addresses.size.toLong(), result.size)
        addresses.forEachIndexed { i, offset -> assertEquals("$op($a, $b) at $i", offset, result.index(i.toLong())) }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}({1},{2})")
        fun cases(): List<Array<String>> = CuteReferenceTest::class.java.getResourceAsStream("/cute/reference.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t').toTypedArray() }.toList()
        }
    }
}
