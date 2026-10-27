package com.xnotes.core.cute

import org.junit.Assert.*
import org.junit.Test

class CuteTvCoordinateTest {
    @Test fun reportedExampleSvgMatchesNvidiaPrintLatexTvInEveryCell() {
        val spec = CuteSpec(a = "((4,8),(2,2)):((32,4),(2,1))",tileM = 16,tileN = 8)
        val e = spec.evaluate()
        val grid = e.grid()
        val rows = javaClass.getResourceAsStream("/cute/user-tv-diagram.tsv")!!.bufferedReader().readLines().filter { !it.startsWith('#') && it.isNotBlank() }.map { it.split('\t').map(String::toInt) }
        val expected = rows.associate { it[0] to it[1] to ("T${it[2]}" to "V${it[3]}") }
        rows.forEach { (m,n,t,v) -> assertEquals(CuteOwner(t,v,t + 32L*v),grid.cell(m,n).owners.single()) }
        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(CuteDiagram.svg(e).byteInputStream()).documentElement
        val nodes = root.childNodes
        val elements = (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        val rects = elements.filter { it.tagName == "rect" && it.getAttribute("width") == "56" }
        val top = rects.minOf { it.getAttribute("y").toInt() }
        val actual = elements.withIndex().filter { (_,r) -> r.tagName == "rect" && r.getAttribute("width") == "56" }.associate { (i,r) ->
            val m = (r.getAttribute("y").toInt()-top)/56
            val n = (r.getAttribute("x").toInt()-56)/56
            (m to n) to (elements[i+1].textContent to elements[i+2].textContent)
        }
        assertEquals(128,actual.size)
        assertEquals(expected,actual)
    }
    @Test fun everyNativeTvPointHasTheSameNestedCoordinateAndGridPosition() {
        val rows = javaClass.getResourceAsStream("/cute/tv-coordinates.tsv")!!.bufferedReader().readLines().filter { !it.startsWith('#') && it.isNotBlank() }.map { it.split('\t') }
        for ((key,points) in rows.groupBy { it.take(3) }) {
            val layout = CuteParser.layout(key[0]); val m = key[1].toInt(); val n = key[2].toInt()
            val grid = CuteSpec(a = key[0],tv = true,tileM = m,tileN = n).evaluate().grid()
            for (p in points) {
                val t = p[3].toInt(); val v = p[4].toInt(); val coord = CuteParser.intTree(p[5]); val index = p[6].toLong(); val offset = p[7].toLong()
                val mn = CuteParser.intTree(p[8]).leaves()
                val label = "$key T$t V$v"
                assertEquals(label,coord,layout.tvCoordinate(t,v))
                assertEquals(label,coord,layout.coordinate(index))
                assertEquals(label,index,layout.tvIndex(t,v))
                assertEquals(label,offset,layout.index(index))
                val cell = grid.cell(mn[0].toInt(),mn[1].toInt())
                assertEquals(label,offset,cell.offset)
                assertTrue(label,cell.owners.contains(CuteOwner(t,v,index)))
            }
            // Native print_latex_tv uses T outer / V inner, retaining the first owner.
            val first = points.groupBy { it[8] }.values.map { it.first() }
            for (p in first) {
                val mn = CuteParser.intTree(p[8]).leaves()
                val cell = grid.cell(mn[0].toInt(),mn[1].toInt())
                assertEquals("T${p[3]}",grid.labels(cell).first())
                assertEquals(p[4].toInt(),cell.owners.first().value)
            }
        }
    }
    @Test fun twoByTwoNaturalCoordinatesNeverInheritStrideOrder() {
        val expected = listOf("(0,0)","(1,0)","(0,1)","(1,1)")
        for (major in listOf(false,true)) {
            val layout = CuteLayout.compact(CuteParser.intTree("((2,2),(2,2))"),major)
            for (t in 0..3) for (v in 0..3) {
                assertEquals("(${expected[t]},${expected[v]})",layout.tvCoordinate(t,v).toString())
                assertEquals(t + 4L * v,layout.tvIndex(t,v))
            }
        }
    }
    @Test fun rightMajorChangesAddressesButNotTvNumbering() {
        val a = CuteLayout.compact(CuteParser.intTree("(2,2)"),true)
        assertEquals("(1,0)",a.tvCoordinate(1,0).toString())
        assertEquals(1L,a.tvIndex(1,0)); assertEquals(2L,a.index(a.tvIndex(1,0)))
        val g = CuteSpec(a = a.toString(),tileM = 2,tileN = 2).evaluate().grid()
        assertEquals(CuteOwner(1,0,1),g.cell(0,1).owners.single())
        assertEquals(CuteOwner(0,1,2),g.cell(1,0).owners.single())
    }
}
