package com.xnotes.gl

import android.opengl.GLES30
import android.os.Build
import android.os.SharedMemory
import android.system.ErrnoException
import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.SlotAllocator
import com.xnotes.core.model.Rgba
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap

/** Where a mesh lives inside the shared buffers. */
class BufferSlice(
    val vertexOffset: Int,
    val vertexCount: Int,
    val indexOffset: Int,
    val indexCount: Int,
)

/**
 * The persistent vertex and index buffers every committed item is tessellated into once. A frame
 * draws slices of these, so per-frame work is a cull and a handful of draw calls rather than a
 * rasterization.
 *
 * Two things shape the vertex format. A position is stored as a coarse chunk index plus a small
 * local offset, never as an absolute coordinate: the canvas is unbounded, and a float absolute
 * position has visibly quantized long before a user has finished panning. Splitting it keeps every
 * number the GPU sees under [CHUNK_SIZE], so precision is the same at the origin and a hundred
 * million pixels away. Colour is per vertex rather than per draw, so a run of items in different
 * colours still collapses into one draw call.
 *
 * A CPU mirror of both buffers is kept. It makes an edit a small `glBufferSubData` instead of a
 * rebuild, and it makes recovering from EGL context loss a re-upload rather than a re-tessellation
 * of the whole document. Nothing here is the source of truth: all of it is derived from the model
 * and can be thrown away and rebuilt.
 */
class GeometryStore {

    /** Mirrors mapped from shared memory, which can be handed back the moment they are replaced. */
    private val mapped: MutableSet<ByteBuffer> = Collections.newSetFromMap(IdentityHashMap())

    private var vertexMirror: ByteBuffer = allocate(INITIAL_VERTICES * VERTEX_STRIDE)
    private var indexMirror: ByteBuffer = allocate(INITIAL_INDICES * INDEX_STRIDE)
    private var vertexWords = vertexMirror.asIntBuffer()
    private var indexWords = indexMirror.asIntBuffer()

    // Bounded and reused even for very large meshes: no second document-sized staging allocation.
    private val packing by lazy { IntArray(PACK_VERTICES * PackedGeometry.WORDS_PER_VERTEX) }
    private val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    private val vertexAllocator = SlotAllocator(INITIAL_VERTICES)
    private val indexAllocator = SlotAllocator(INITIAL_INDICES)

    private var vbo = 0
    private var ibo = 0
    private var uploadedGen = -1

    /** Whole-buffer re-upload pending, because a buffer grew or the context was rebuilt. */
    private var fullUpload = true

    // Dirty byte ranges since the last upload, coalesced into one span each.
    private var vertexDirtyLo = Int.MAX_VALUE
    private var vertexDirtyHi = 0
    private var indexDirtyLo = Int.MAX_VALUE
    private var indexDirtyHi = 0

    val vertexCapacity: Int get() = vertexAllocator.capacity
    val indexCapacity: Int get() = indexAllocator.capacity
    val usedVertices: Int get() = vertexAllocator.used
    val usedIndices: Int get() = indexAllocator.used

    /** Set once a buffer could not grow, so an item was left undrawn; cleared with the store. */
    var outOfMemory = false
        private set

    /** Bytes the GPU holds for this store, which is the mirror's size on both sides. */
    val gpuBytes: Long
        get() = vertexAllocator.capacity.toLong() * VERTEX_STRIDE +
            indexAllocator.capacity.toLong() * INDEX_STRIDE

    /** Bytes actually referenced by live geometry, so fragmentation shows as the gap to [gpuBytes]. */
    val liveBytes: Long
        get() = vertexAllocator.used.toLong() * VERTEX_STRIDE +
            indexAllocator.used.toLong() * INDEX_STRIDE

    /**
     * Write [mesh] into the buffers in [color] and return where it landed. Positions are split into
     * chunk plus local here rather than in the tessellator, so the tessellator stays pure doubles.
     * A mesh carrying its own per-vertex colours (a gradient fill) uses those instead of [color].
     */
    fun put(mesh: MeshData, color: Rgba): BufferSlice? {
        if (mesh.isEmpty) return null
        val vCount = mesh.vertexCount
        val iCount = mesh.indices.size
        val vOffset = vertexAllocator.allocate(vCount)
            ?: (if (growVertices(vCount)) vertexAllocator.allocate(vCount) else null)
            ?: return null
        val iOffset = indexAllocator.allocate(iCount)
            ?: (if (growIndices(iCount)) indexAllocator.allocate(iCount) else null)
            ?: run {
                vertexAllocator.free(vOffset, vCount)
                return null
            }

        val packed = packing
        vertexWords.clear()
        vertexWords.position(vOffset * PackedGeometry.WORDS_PER_VERTEX)
        var from = 0
        while (from < vCount) {
            val count = minOf(PACK_VERTICES, vCount - from)
            PackedGeometry.vertices(mesh, color, from, count, packed, littleEndian)
            vertexWords.put(packed, 0, count * PackedGeometry.WORDS_PER_VERTEX)
            from += count
        }
        markVertexDirty(vOffset, vCount)

        indexWords.clear()
        indexWords.position(iOffset)
        from = 0
        while (from < iCount) {
            val count = minOf(packed.size, iCount - from)
            // Mesh indices are relative to their own first vertex; rebase into the shared buffer.
            for (i in 0 until count) packed[i] = vOffset + mesh.indices[from + i]
            indexWords.put(packed, 0, count)
            from += count
        }
        markIndexDirty(iOffset, iCount)

        return BufferSlice(vOffset, vCount, iOffset, iCount)
    }

    /** Recolour a slice in place, without re-tessellating. */
    fun recolor(slice: BufferSlice, color: Rgba) {
        val rgba = PackedGeometry.rgbaWord(color.toArgb(), littleEndian)
        var p = slice.vertexOffset * PackedGeometry.WORDS_PER_VERTEX + 3
        for (i in 0 until slice.vertexCount) {
            vertexWords.put(p, rgba)
            p += PackedGeometry.WORDS_PER_VERTEX
        }
        markVertexDirty(slice.vertexOffset, slice.vertexCount)
    }

    fun free(slice: BufferSlice) {
        vertexAllocator.free(slice.vertexOffset, slice.vertexCount)
        indexAllocator.free(slice.indexOffset, slice.indexCount)
        // The bytes are left as they are: nothing indexes them any more, and clearing them would
        // dirty a range for no visible effect.
    }

    fun clear() {
        vertexAllocator.reset()
        indexAllocator.reset()
        outOfMemory = false
    }

    /** Room for [vertices] and [indices] more in one step, so a bulk load does not double its way up. */
    fun reserve(vertices: Long, indices: Long) {
        // A reservation that cannot be had is not a failure: the puts behind it grow as they need to.
        val vertexCap = vertexAllocator.reservedCapacity(vertices)
        if (vertexCap != null && vertexCap > vertexAllocator.capacity) {
            resize(vertexMirror, vertexAllocator, vertexCap, VERTEX_STRIDE)?.let {
                vertexMirror = it
                vertexWords = it.asIntBuffer()
            }
        }
        val indexCap = indexAllocator.reservedCapacity(indices)
        if (indexCap != null && indexCap > indexAllocator.capacity) {
            resize(indexMirror, indexAllocator, indexCap, INDEX_STRIDE)?.let {
                indexMirror = it
                indexWords = it.asIntBuffer()
            }
        }
    }

    // --- GL lifecycle ---

    /** Called on the GL thread after a context is created; every previous name is already gone. */
    fun onContextCreated(gen: Int) {
        vbo = 0
        ibo = 0
        uploadedGen = gen
        fullUpload = true
        val names = IntArray(2)
        GLES30.glGenBuffers(2, names, 0)
        vbo = names[0]
        ibo = names[1]
    }

    /** Hand the GPU buffers back. Only a store that outlives nothing needs this; the scene's dies
     *  with its context instead. */
    fun release() {
        if (vbo != 0 || ibo != 0) GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        vbo = 0
        ibo = 0
        uploadedGen = -1
    }

    /** Push pending changes to the GPU and bind the buffers. Returns false when there is nothing. */
    fun bindForDraw(gen: Int): Boolean {
        if (gen != uploadedGen || vbo == 0 || ibo == 0) return false
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        if (fullUpload) {
            // Upload only the part in use: reading a shared mirror's untouched pages would commit them.
            val vertexBytes = vertexAllocator.capacity * VERTEX_STRIDE
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBytes, null, GLES30.GL_DYNAMIC_DRAW)
            val indexBytes = indexAllocator.capacity * INDEX_STRIDE
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, null, GLES30.GL_DYNAMIC_DRAW)
            fullUpload = false
            clearDirty()
            markVertexDirty(0, vertexAllocator.end)
            markIndexDirty(0, indexAllocator.end)
        }
        if (vertexDirtyLo < vertexDirtyHi) {
            vertexMirror.position(vertexDirtyLo)
            vertexMirror.limit(vertexDirtyHi)
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER, vertexDirtyLo, vertexDirtyHi - vertexDirtyLo, vertexMirror.slice(),
            )
            vertexMirror.clear()
        }
        if (indexDirtyLo < indexDirtyHi) {
            indexMirror.position(indexDirtyLo)
            indexMirror.limit(indexDirtyHi)
            GLES30.glBufferSubData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, indexDirtyLo, indexDirtyHi - indexDirtyLo, indexMirror.slice(),
            )
            indexMirror.clear()
        }
        clearDirty()
        return true
    }

    /** Point the ink program's attributes at the bound vertex buffer. */
    fun bindAttributes(program: InkShader) {
        val pos = program.attribLocal
        val chunk = program.attribChunk
        val color = program.attribColor
        if (pos >= 0) {
            GLES30.glEnableVertexAttribArray(pos)
            GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, 0)
        }
        if (chunk >= 0) {
            GLES30.glEnableVertexAttribArray(chunk)
            GLES30.glVertexAttribPointer(chunk, 2, GLES30.GL_SHORT, false, VERTEX_STRIDE, 8)
        }
        if (color >= 0) {
            GLES30.glEnableVertexAttribArray(color)
            GLES30.glVertexAttribPointer(color, 4, GLES30.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12)
        }
        val offset = program.attribOffset
        if (offset >= 0) {
            GLES30.glEnableVertexAttribArray(offset)
            GLES30.glVertexAttribPointer(offset, 2, GLES30.GL_SHORT, false, VERTEX_STRIDE, 16)
        }
    }

    /** Draw [indexCount] indices starting at [indexOffset]; the buffers must already be bound. */
    fun drawRange(indexOffset: Int, indexCount: Int) {
        if (indexCount <= 0) return
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, indexOffset * INDEX_STRIDE,
        )
    }

    // --- internals ---

    private fun growVertices(need: Int): Boolean {
        val cap = vertexAllocator.grownCapacity(need)
        val next = cap?.let { resize(vertexMirror, vertexAllocator, it, VERTEX_STRIDE) }
        if (next == null) outOfMemory = true else {
            vertexMirror = next
            vertexWords = next.asIntBuffer()
        }
        return next != null
    }

    private fun growIndices(need: Int): Boolean {
        val cap = indexAllocator.grownCapacity(need)
        val next = cap?.let { resize(indexMirror, indexAllocator, it, INDEX_STRIDE) }
        if (next == null) outOfMemory = true else {
            indexMirror = next
            indexWords = next.asIntBuffer()
        }
        return next != null
    }

    /** [mirror] copied into a buffer of [cap] slots, or null when the device cannot spare one. */
    private fun resize(mirror: ByteBuffer, allocator: SlotAllocator, cap: Int, stride: Int): ByteBuffer? {
        val bytes = cap.toLong() * stride
        if (bytes > Int.MAX_VALUE) return null
        // One big request failing leaves the heap as it was, so the canvas can carry on without it.
        val next = try {
            allocate(bytes.toInt())
        } catch (e: OutOfMemoryError) {
            return null
        }
        mirror.position(0)
        mirror.limit(allocator.end * stride)
        next.put(mirror)
        next.clear()
        free(mirror)
        allocator.grow(cap)
        fullUpload = true
        return next
    }

    private fun markVertexDirty(offset: Int, count: Int) {
        val lo = offset * VERTEX_STRIDE
        val hi = (offset + count) * VERTEX_STRIDE
        if (lo < vertexDirtyLo) vertexDirtyLo = lo
        if (hi > vertexDirtyHi) vertexDirtyHi = hi
    }

    private fun markIndexDirty(offset: Int, count: Int) {
        val lo = offset * INDEX_STRIDE
        val hi = (offset + count) * INDEX_STRIDE
        if (lo < indexDirtyLo) indexDirtyLo = lo
        if (hi > indexDirtyHi) indexDirtyHi = hi
    }

    private fun clearDirty() {
        vertexDirtyLo = Int.MAX_VALUE
        vertexDirtyHi = 0
        indexDirtyLo = Int.MAX_VALUE
        indexDirtyHi = 0
    }

    /** Mirror memory, mapped outside the Java heap where it can be: a direct buffer is heap on Android. */
    private fun allocate(bytes: Int): ByteBuffer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val buffer = SharedMemory.create(SHARED_NAME, bytes).use { it.mapReadWrite() }
                mapped.add(buffer)
                return buffer.order(ByteOrder.nativeOrder())
            } catch (e: ErrnoException) {
                // No shared memory to be had, so the heap it is.
            }
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    private fun free(buffer: ByteBuffer) {
        if (!mapped.remove(buffer)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) SharedMemory.unmap(buffer)
    }

    companion object {
        /** Content pixels a chunk spans; see [CanvasProjection], which the shader agrees with. */
        const val CHUNK_SIZE = CanvasProjection.CHUNK_SIZE

        const val VERTEX_STRIDE = PackedGeometry.WORDS_PER_VERTEX * 4

        /** Fixed-point steps per content pixel in the stored spine offset. */
        const val OFFSET_SCALE = CanvasProjection.OFFSET_SCALE

        /** [v] content pixels as the fixed-point short the vertex format stores. */
        fun quantizeOffset(v: Double): Short = PackedGeometry.quantizeOffset(v)
        const val INDEX_STRIDE = 4

        private const val INITIAL_VERTICES = 8192
        private const val INITIAL_INDICES = 24576
        private const val PACK_VERTICES = 1024

        private const val SHARED_NAME = "xnotes-geometry"

        /** Chunk index a content coordinate falls in, clamped to what a short can hold. */
        fun chunkIndex(v: Double): Double = CanvasProjection.chunkIndex(v)
    }
}
