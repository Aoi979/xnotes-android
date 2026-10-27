/*
 * Algorithms adapted from NVIDIA CUTLASS cute/layout.hpp (dc45f979ae33).
 * Copyright (c) 2023 - 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * Full notice: app/src/main/assets/licenses/cutlass-cute.txt
 */
package com.xnotes.core.cute

/** Integer-only static Layout algebra, preserving CuTe's tuple structure, including size-1 modes. */
object LayoutAlgebra {
    private fun flat(s: List<Long>, d: List<Long>) = CuteLayout(numbers(s), numbers(d))
    private fun ceil(a: Long, b: Long): Long { cuteRequire(b > 0, "divisibility"); return a / b + if (a % b == 0L) 0 else 1 }

    fun coalesce(a: CuteLayout, extended: Boolean = false): CuteLayout {
        val (s, d) = coalescedModes(a, extended)
        return flat(s, d)
    }

    // coalesce_x may temporarily double the size of a valid input. Keep this intermediate
    // symbolic, so the public domain limit is applied to actual inputs/results only.
    private fun coalescedModes(a: CuteLayout, extended: Boolean): Pair<List<Long>, List<Long>> {
        val ss = a.shape.leaves().toMutableList()
        val ds = a.stride.leaves()
        if (extended && ss.last() == 1L) ss[ss.lastIndex] = 2
        val s = mutableListOf<Long>()
        val d = mutableListOf<Long>()
        for (i in ss.indices.reversed()) {
            if (ss[i] == 1L) continue
            if (s.isNotEmpty() && product(ss[i], ds[i]) == d[0]) {
                s[0] = product(s[0], ss[i]); d[0] = ds[i]
            } else { s.add(0, ss[i]); d.add(0, ds[i]) }
        }
        return if (s.isEmpty()) listOf(1L) to listOf(0L) else s to d
    }

    fun coalesce(a: CuteLayout, profile: CuteOperand): CuteLayout = when (profile) {
        is CuteOperand.Tile -> {
            cuteRequire(profile.values.size <= a.rank, "tiler_rank")
            CuteLayout.join(a.modes().mapIndexed { i, mode -> if (i < profile.values.size) coalesce(mode, profile.values[i]) else mode })
        }
        else -> coalesce(a)
    }

    fun filter(a: CuteLayout): CuteLayout {
        val d = a.stride.leaves()
        return coalesce(flat(a.shape.leaves().mapIndexed { i, s -> if (d[i] == 0L) 1 else s }, d))
    }

    fun composition(a: CuteLayout, b: CuteLayout): CuteLayout {
        val (ls, ld) = coalescedModes(a, extended = true)
        fun compose(rhs: CuteLayout): CuteLayout {
            if (rhs.shape is IntTree.Tuple) return CuteLayout.join(rhs.modes().map { compose(it) })
            val s = rhs.size
            val d = (rhs.stride as IntTree.Num).value
            if (d == 0L) return rhs
            if (ls.size == 1) return CuteLayout.scalar(s, product(d, ld.single()))
            val rs = mutableListOf<Long>(); val rd = mutableListOf<Long>()
            var restS = s; var restD = d
            for (i in 0 until ls.lastIndex) {
                cuteRequire(restD % ls[i] == 0L || restD < ls[i], "stride_divisibility", "$restD / ${ls[i]}")
                val nextS = ceil(ls[i], restD)
                val nextD = ceil(restD, ls[i])
                if (nextS != 1L && restS != 1L) {
                    val newS = minOf(nextS, restS)
                    cuteRequire(restS % newS == 0L, "shape_divisibility", "$restS / $newS")
                    rs += newS; rd += product(restD, ld[i]); restS /= newS
                }
                restD = nextD
            }
            if (rs.isEmpty()) return CuteLayout.scalar(restS, product(restD, ld.last()))
            if (restS != 1L) { rs += restS; rd += product(restD, ld.last()) }
            return flat(rs, rd)
        }
        val result = compose(b)
        // CuTe's static divisibility assertions are deliberately weak. Reject non-admissible
        // inputs rather than showing a Layout whose offsets disagree with the stated A(B(i)).
        for (i in 0 until b.size) cuteRequire(result.index(i) == a.index(b.index(i)), "shape_divisibility")
        return result
    }

    fun composition(a: CuteLayout, b: CuteOperand): CuteLayout = when (b) {
        is CuteOperand.Layout -> composition(a, b.value)
        is CuteOperand.Integer -> composition(a, CuteLayout.scalar(b.value))
        CuteOperand.All -> a
        is CuteOperand.Tile -> {
            cuteRequire(b.values.size <= a.rank, "tiler_rank")
            CuteLayout.join(b.values.mapIndexed { i, v -> composition(a.mode(i), v) })
        }
    }

    fun complement(a: CuteLayout, target: IntTree? = null): CuteLayout {
        if (target != null) cuteRequire(target.leaves().all { it > 0 } && target.size() in 1..MAX_LAYOUT_SIZE, "target")
        val f = filter(a)
        if (f.stride is IntTree.Num && f.stride.value == 0L) return CuteLayout.scalar(target?.size() ?: 1, 1)
        val ss = f.shape.leaves(); val ds = f.stride.leaves()
        val order = ds.indices.sortedBy { ds[it] }
        val rs = mutableListOf<Long>(); val rd = mutableListOf<Long>()
        var last = 1L
        for (i in order) {
            val gap = ds[i] / last
            cuteRequire(gap > 0, "non_injective_complement")
            rs += gap; rd += last
            last = product(ds[i], ss[i])
        }
        if (target != null) {
            fun divideShape(shape: IntTree, divisor: Long): IntTree = when (shape) {
                is IntTree.Num -> IntTree.Num(ceil(shape.value, divisor))
                is IntTree.Tuple -> {
                    var rest = divisor
                    tuple(shape.children.map { child -> divideShape(child, rest).also { rest = ceil(rest, child.size()) } })
                }
            }
            rs += divideShape(target, last).size(); rd += last
            return coalesce(flat(rs, rd))
        }
        // The final size-1 mode carries the complement outside its domain (current CuTe).
        val c = coalesce(flat(rs, rd))
        if (c.size == 1L) return CuteLayout.scalar(1, last)
        return CuteLayout(IntTree.Tuple(c.shape.modes + IntTree.Num(1)), IntTree.Tuple(c.stride.modes + IntTree.Num(last)))
    }

    fun rightInverse(a: CuteLayout): CuteLayout {
        val f = coalesce(a); val s = f.shape.leaves(); val d = f.stride.leaves()
        val prefix = prefixes(s)
        val rs = mutableListOf(1L); val rd = mutableListOf(0L)
        var current = 1L
        for (i in d.indices.sortedBy { d[it] }) if (d[i] == current) {
            rs += s[i]; rd += prefix[i]; current = product(s[i], d[i])
        }
        return coalesce(flat(rs, rd))
    }

    fun leftInverse(a: CuteLayout): CuteLayout {
        val f = coalesce(a); val s = f.shape.leaves(); val d = f.stride.leaves()
        val prefix = prefixes(s); val order = d.indices.sortedBy { d[it] }
        val rs = mutableListOf<Long>(); val rd = mutableListOf(0L)
        var size = 1L
        for (i in order) if (d[i] != 0L) {
            cuteRequire(d[i] % size == 0L, "inverse_divisibility")
            rs += d[i] / size; rd += prefix[i]; size = d[i]
        }
        rs += s[order.last()]
        return coalesce(flat(rs, rd))
    }

    private fun prefixes(s: List<Long>): List<Long> {
        var p = 1L
        return s.map { v -> p.also { p = product(p, v) } }
    }

    fun logical(a: CuteLayout, b: CuteOperand, divide: Boolean): CuteLayout = when (b) {
        is CuteOperand.Tile -> {
            cuteRequire(b.values.size <= a.rank, "tiler_rank")
            CuteLayout.join(a.modes().mapIndexed { i, mode -> if (i < b.values.size) logical(mode, b.values[i], divide) else mode })
        }
        CuteOperand.All -> a
        else -> {
            val layout = if (b is CuteOperand.Layout) b.value else CuteLayout.scalar((b as CuteOperand.Integer).value)
            if (divide) composition(a, CuteLayout.join(listOf(layout, complement(layout, coalesce(a).shape))))
            else CuteLayout.join(listOf(a, composition(complement(a), layout)))
        }
    }

    private fun unzip(a: CuteLayout, tiler: CuteOperand): CuteLayout {
        if (tiler !is CuteOperand.Tile) { cuteRequire(a.rank == 2, "zip_rank"); return a }
        cuteRequire(tiler.values.size <= a.rank, "tiler_rank")
        val split = tiler.values.mapIndexed { i, t -> unzip(a.mode(i), t) }
        return CuteLayout.join(listOf(CuteLayout.join(split.map { it.mode(0) }), CuteLayout.join(split.map { it.mode(1) } + a.modes().drop(tiler.values.size))))
    }

    fun divideOrProduct(a: CuteLayout, b: CuteOperand, divide: Boolean, grouping: String): CuteLayout {
        val logical = logical(a, b, divide)
        if (grouping == "logical") return logical
        val z = unzip(logical, b)
        return when (grouping) {
            "zipped" -> z
            "tiled" -> CuteLayout.join(listOf(z.mode(0)) + z.mode(1).modes())
            "flat" -> CuteLayout.join(z.mode(0).modes() + z.mode(1).modes())
            else -> throw CuteException("operation")
        }
    }

    fun orderedProduct(a: CuteLayout, b: CuteLayout, raked: Boolean): CuteLayout {
        val rank = maxOf(a.rank, b.rank)
        fun pad(l: CuteLayout) = CuteLayout.join(l.modes() + List(rank - l.rank) { CuteLayout.scalar(1, 0) })
        val r = logical(pad(a), CuteOperand.Layout(pad(b)), false)
        val x = r.mode(if (raked) 1 else 0); val y = r.mode(if (raked) 0 else 1)
        return CuteLayout.join((0 until rank).map { CuteLayout.join(listOf(x.mode(it), y.mode(it))) })
    }
}

enum class CuteOperation(val id: String, val arity: Int) {
    LAYOUT("make_layout", 1), COALESCE("coalesce", 1), FILTER("filter", 1),
    COMPOSITION("composition", 2), COMPLEMENT("complement", 2),
    RIGHT_INVERSE("right_inverse", 1), LEFT_INVERSE("left_inverse", 1),
    LOGICAL_DIVIDE("logical_divide", 2), ZIPPED_DIVIDE("zipped_divide", 2), TILED_DIVIDE("tiled_divide", 2), FLAT_DIVIDE("flat_divide", 2),
    LOGICAL_PRODUCT("logical_product", 2), ZIPPED_PRODUCT("zipped_product", 2), TILED_PRODUCT("tiled_product", 2), FLAT_PRODUCT("flat_product", 2),
    BLOCKED_PRODUCT("blocked_product", 2), RAKED_PRODUCT("raked_product", 2);

    fun apply(a: CuteLayout, argument: String): CuteLayout = when (this) {
        LAYOUT -> a
        COALESCE -> if (argument.isBlank()) LayoutAlgebra.coalesce(a) else LayoutAlgebra.coalesce(a, CuteParser.operand(argument))
        FILTER -> LayoutAlgebra.filter(a)
        COMPOSITION -> LayoutAlgebra.composition(a, CuteParser.operand(argument))
        COMPLEMENT -> LayoutAlgebra.complement(a, if (argument.isBlank()) null else CuteParser.intTree(argument))
        RIGHT_INVERSE -> LayoutAlgebra.rightInverse(a)
        LEFT_INVERSE -> LayoutAlgebra.leftInverse(a)
        BLOCKED_PRODUCT, RAKED_PRODUCT -> LayoutAlgebra.orderedProduct(a, CuteParser.layout(argument), this == RAKED_PRODUCT)
        else -> LayoutAlgebra.divideOrProduct(a, CuteParser.operand(argument), id.endsWith("divide"), id.substringBefore('_'))
    }
}
