package com.xnotes.gl

import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PackedGeometryTest {
    private val mesh = MeshData(
        doubleArrayOf(-1.0, 0.0, 4096.5, -4096.25, 40_000_037.5, -9_000_000.125),
        doubleArrayOf(1.25, -2.5, 1e9, Double.NaN, Double.NEGATIVE_INFINITY, 0.01),
        intArrayOf(0, 1, 2),
    )

    private fun packed(mesh: MeshData, order: ByteOrder, from: Int = 0, count: Int = mesh.vertexCount): ByteBuffer {
        val words = IntArray(count * PackedGeometry.WORDS_PER_VERTEX)
        PackedGeometry.vertices(mesh, Rgba(0x12, 0x34, 0x56, 0x78), from, count, words, order == ByteOrder.LITTLE_ENDIAN)
        return ByteBuffer.allocate(words.size * 4).order(order).also { it.asIntBuffer().put(words) }
    }

    @Test fun gpuAttributesKeepPositionsSignedChunksOffsetsAndRgbaInBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val b = packed(mesh, order)
            val chunks = arrayOf(-1 to 0, 1 to -2, 9765 to -2198)
            val offsets = arrayOf(80 to -160, 32767 to 0, 0 to 0)
            for (i in 0..2) {
                val p = i * 20
                val (cx, cy) = chunks[i]
                assertEquals(cx.toShort(), b.getShort(p + 8))
                assertEquals(cy.toShort(), b.getShort(p + 10))
                assertEquals(mesh.positions[2 * i], b.getFloat(p).toDouble() + cx * 4096.0, 0.0)
                assertEquals(mesh.positions[2 * i + 1], b.getFloat(p + 4).toDouble() + cy * 4096.0, 0.0)
                for ((j, value) in listOf(0x12, 0x34, 0x56, 0x78).withIndex()) {
                    assertEquals(value, b.get(p + 12 + j).toInt() and 0xff)
                }
                assertEquals(offsets[i].first.toShort(), b.getShort(p + 16))
                assertEquals(offsets[i].second.toShort(), b.getShort(p + 18))
            }
        }
    }

    @Test fun aPartialBatchUsesItsOwnGradientVerticesAndLeavesTheScratchTailAlone() {
        val gradient = MeshData(mesh.positions, mesh.offsets, mesh.indices, intArrayOf(0xff010203.toInt(), 0x7f456789, 0x10203040))
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val b = packed(gradient, order, from = 1, count = 1)
            assertEquals(0.5f, b.getFloat(0), 0f)
            assertEquals(0x45, b.get(12).toInt() and 0xff)
            assertEquals(0x67, b.get(13).toInt() and 0xff)
            assertEquals(0x89, b.get(14).toInt() and 0xff)
            assertEquals(0x7f, b.get(15).toInt() and 0xff)
        }
        val scratch = IntArray(10) { 0x5a5a5a5a }
        PackedGeometry.vertices(gradient, Rgba(0, 0, 0), 2, 1, scratch, true)
        for (i in 5..9) assertEquals(0x5a5a5a5a, scratch[i])
    }

    @Test fun aShortGradientFallsBackToTheUniformColor() {
        val short = MeshData(mesh.positions, mesh.offsets, mesh.indices, intArrayOf(0))
        assertEquals(packed(mesh, ByteOrder.LITTLE_ENDIAN), packed(short, ByteOrder.LITTLE_ENDIAN))
    }
}
