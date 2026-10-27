/* NVIDIA CUTLASS cute/swizzle.hpp, BSD-3-Clause; see assets/licenses/cutlass-cute.txt. */
package com.xnotes.core.cute

import kotlin.math.abs

/** Element-offset Swizzle<B,M,S>; byte-address views explicitly apply it to bytes instead. */
data class CuteSwizzle(val bits: Int = 3, val base: Int = 3, val shift: Int = 3, val offset: Long = 0) {
    fun validate() {
        cuteRequire(bits in 0..10 && base >= 0 && abs(shift.toLong()) >= bits && base.toLong() + abs(shift.toLong()) + bits <= 30 && offset >= 0 && offset < (1L shl 30), "swizzle")
    }
    val sourceMask: Long get() = ((1L shl bits) - 1) shl (base + maxOf(0,shift))
    val targetMask: Long get() = ((1L shl bits) - 1) shl (base - minOf(0,shift))
    fun apply(value: Long): Long {
        val source = value and sourceMask
        return value xor if (shift >= 0) source.ushr(shift) else source.shl(-shift)
    }
    fun map(value: Long) = apply(Math.addExact(offset,value))
    fun cpp() = "Swizzle<$bits,$base,$shift>{}"
    override fun toString() = "Swizzle<$bits,$base,$shift> o $offset"
}
