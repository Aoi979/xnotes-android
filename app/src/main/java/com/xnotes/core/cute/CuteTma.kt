package com.xnotes.core.cute

/** Tiled tensor-map analysis for ordinary 8/16/32/64-bit elements, interleave NONE.
 * Dimension 0 is contiguous, as in cuTensorMapEncodeTiled. No opaque CUDA descriptor
 * or GPU instruction is emulated. Global strides and all displayed addresses are BYTES.
 */
data class CuteTmaSpec(
    val globalShape: String = "(128,64)", val globalStrides: String = "(256)",
    val box: String = "(64,8)", val origin: String = "(0,0)", val steps: String = "(1,1)",
    val elementBytes: Int = 2, val swizzleBytes: Int = 128,
    val globalBase: Long = 0, val sharedBase: Long = 0,
) {
    fun analyze() = CuteTmaAnalysis(this)
}

internal fun integerVector(value: String): List<Long> {
    cuteRequire(value.length <= 2048, "input_limit")
    val text = value.trim().removeSurrounding("(", ")").trim()
    if (text.isEmpty()) return emptyList()
    return text.split(',').also { cuteRequire(it.size <= 5, "tma_rank") }.map {
        it.trim().toLongOrNull() ?: throw CuteException("integer_tuple")
    }
}

data class TmaElement(val coordinate: List<Long>, val globalByte: Long?, val sharedByte: Long, val bank: Int, val outOfBounds: Boolean)

class CuteTmaAnalysis(val spec: CuteTmaSpec) {
    val global = integerVector(spec.globalShape)
    val box = integerVector(spec.box)
    val origin = integerVector(spec.origin)
    val requestedSteps = integerVector(spec.steps)
    val rank = global.size
    val steps: List<Long>
    val byteStrides: List<Long>
    val sampleShape: List<Long>
    val layout: CuteLayout
    val rowPitchBytes: Long
    val allocationBytes: Long
    val transferBytes: Long
    val swizzle: CuteSwizzle?
    init {
        cuteRequire(rank in 1..5 && box.size == rank && origin.size == rank && requestedSteps.size == rank, "tma_rank")
        cuteRequire(spec.elementBytes in listOf(1,2,4,8), "tma_type")
        cuteRequire(global.all { it in 1..(1L shl 32) }, "tma_shape")
        cuteRequire(box.all { it in 1..256 } && requestedSteps.all { it in 1..8 }, "tma_box")
        cuteRequire(origin.all { it in Int.MIN_VALUE..Int.MAX_VALUE }, "tma_origin")
        cuteRequire(spec.swizzleBytes in listOf(0,32,64,128), "tma_swizzle")
        cuteRequire(spec.globalBase >= 0 && spec.globalBase % (if (spec.swizzleBytes == 0) 16 else 128) == 0L && spec.sharedBase in 0..(1L shl 30) && spec.sharedBase % 128 == 0L, "tma_alignment")
        val supplied = integerVector(spec.globalStrides)
        cuteRequire(supplied.size == rank - 1, "tma_strides")
        byteStrides = listOf(spec.elementBytes.toLong()) + supplied
        for (i in 1 until rank) {
            cuteRequire(byteStrides[i] > 0 && byteStrides[i] < (1L shl 40) && byteStrides[i] % 16 == 0L && byteStrides[i] >= Math.multiplyExact(byteStrides[i-1],global[i-1]), "tma_strides")
        }
        val innerBytes = box[0] * spec.elementBytes
        cuteRequire(innerBytes % 16 == 0L, "tma_inner")
        cuteRequire(spec.swizzleBytes == 0 || innerBytes <= spec.swizzleBytes, "tma_swizzle")
        // TMA ignores elementStrides[0] when interleave is NONE.
        steps = requestedSteps.mapIndexed { i,s -> if (i == 0) 1 else s }
        sampleShape = box.zip(steps).map { (b,s) -> (b + s - 1) / s }
        layout = CuteLayout.compact(numbers(sampleShape))
        rowPitchBytes = maxOf(innerBytes,spec.swizzleBytes.toLong())
        allocationBytes = Math.multiplyExact(rowPitchBytes,layout.size / sampleShape[0])
        transferBytes = layout.size * spec.elementBytes
        swizzle = when (spec.swizzleBytes) { 32 -> CuteSwizzle(1,4,3); 64 -> CuteSwizzle(2,4,3); 128 -> CuteSwizzle(3,4,3); else -> null }
    }
    fun element(index: Long): TmaElement {
        cuteRequire(index in 0 until layout.size,"negative_coordinate")
        val local = layout.coordinate(index).leaves()
        val coord = local.indices.map { origin[it] + local[it] * steps[it] }
        val outside = coord.indices.any { coord[it] < 0 || coord[it] >= global[it] }
        val address = if (outside) null else coord.indices.fold(spec.globalBase) { acc,i -> Math.addExact(acc,Math.multiplyExact(coord[i],byteStrides[i])) }
        val raw = (index % sampleShape[0]) * spec.elementBytes + (index / sampleShape[0]) * rowPitchBytes
        val absolute = spec.sharedBase + raw
        val shared = (swizzle?.apply(absolute) ?: absolute) - spec.sharedBase
        return TmaElement(coord,address,shared,((spec.sharedBase + shared) / 4 % 32).toInt(),outside)
    }
    fun cpp(): String = buildString {
        append("// Host code; include <cuda.h>. global_ptr must name an actual CUDA allocation.\n")
        append("// Address labels use GMEM base ${spec.globalBase} B as a numeric reference; substitute your real global_ptr.\n")
        append("// Validated layout constraints only; check the CUDA return value on a CC 9.0+ device.\n")
        append("alignas(64) CUtensorMap tensor_map{};\n")
        append("cuuint64_t global_dims[] = {${global.joinToString()}};\n")
        append("cuuint64_t global_strides[] = {${byteStrides.drop(1).joinToString().ifEmpty { "0" }}};\n")
        append("cuuint32_t box_dims[] = {${box.joinToString()}};\n")
        append("cuuint32_t element_strides[] = {${requestedSteps.joinToString()}};\n")
        append("CUresult status = cuTensorMapEncodeTiled(&tensor_map, CU_TENSOR_MAP_DATA_TYPE_UINT${spec.elementBytes * 8}, $rank,\n")
        append("  global_ptr, global_dims, global_strides, box_dims, element_strides,\n  CU_TENSOR_MAP_INTERLEAVE_NONE, CU_TENSOR_MAP_SWIZZLE_${if (spec.swizzleBytes == 0) "NONE" else "${spec.swizzleBytes}B"},\n  CU_TENSOR_MAP_L2_PROMOTION_NONE, CU_TENSOR_MAP_FLOAT_OOB_FILL_NONE);\n")
        append("// SMEM allocation >= $allocationBytes bytes, alignment 128 B; selected base offset ${spec.sharedBase} B.\n")
        append("// Transfer payload $transferBytes B. GMEM origin (${origin.joinToString()}); dimension 0 is contiguous.\n")
    }
}
