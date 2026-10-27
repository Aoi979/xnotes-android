package com.xnotes.gl

import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba

/** The existing 20-byte vertex format, assembled on the heap before a bulk native-memory copy. */
internal object PackedGeometry {
    const val WORDS_PER_VERTEX = 5

    fun vertices(mesh: MeshData, color: Rgba, from: Int, count: Int, into: IntArray, littleEndian: Boolean) {
        val perVertex = mesh.colors?.takeIf { it.size >= mesh.vertexCount }
        val solid = rgbaWord(color.toArgb(), littleEndian)
        var out = 0
        for (i in from until from + count) {
            val x = mesh.positions[2 * i]
            val y = mesh.positions[2 * i + 1]
            val cx = CanvasProjection.chunkIndex(x)
            val cy = CanvasProjection.chunkIndex(y)
            into[out++] = (x - cx * CanvasProjection.CHUNK_SIZE).toFloat().toRawBits()
            into[out++] = (y - cy * CanvasProjection.CHUNK_SIZE).toFloat().toRawBits()
            into[out++] = shorts(cx.toInt().toShort(), cy.toInt().toShort(), littleEndian)
            into[out++] = if (perVertex == null) solid else rgbaWord(perVertex[i], littleEndian)
            into[out++] = shorts(quantizeOffset(mesh.offsets[2 * i]), quantizeOffset(mesh.offsets[2 * i + 1]), littleEndian)
        }
    }

    fun rgbaWord(argb: Int, littleEndian: Boolean): Int = if (littleEndian) {
        (argb and 0xff00ff00.toInt()) or ((argb ushr 16) and 0xff) or ((argb and 0xff) shl 16)
    } else {
        (argb shl 8) or (argb ushr 24)
    }

    private fun shorts(first: Short, second: Short, littleEndian: Boolean): Int = if (littleEndian) {
        (first.toInt() and 0xffff) or (second.toInt() shl 16)
    } else {
        (first.toInt() shl 16) or (second.toInt() and 0xffff)
    }

    fun quantizeOffset(v: Double): Short {
        if (!v.isFinite()) return 0
        return (v * CanvasProjection.OFFSET_SCALE).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }
}
