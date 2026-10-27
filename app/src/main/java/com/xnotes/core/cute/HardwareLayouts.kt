/* Adapted from NVIDIA CUTLASS cute/atom/{mma_atom,copy_atom}.hpp, dc45f979ae33.
 * Copyright (c) 2023 - 2026 NVIDIA CORPORATION & AFFILIATES. BSD-3-Clause.
 * Full notice: assets/licenses/cutlass-cute.txt. */
package com.xnotes.core.cute

data class MmaAtom(val id: String, val cppType: String, val mnk: String, val thr: String,
                   val a: String, val b: String, val c: String, val descriptorA: Boolean, val descriptorB: Boolean) {
    val shape get() = CuteParser.intTree(mnk).leaves()
    fun descriptor(part: String) = (part == "A" && descriptorA) || (part == "B" && descriptorB)
    fun layout(part: String) = CuteParser.layout(when (part) { "A" -> a; "B" -> b; "C" -> c; else -> throw CuteException("operand") })
}

data class CopyAtom(val id: String, val operation: String, val valueType: String, val bits: Int,
                    val thr: String, val source: String, val destination: String, val reference: String) {
    val cppType get() = "Copy_Atom<$operation,$valueType>"
    fun layout(part: String) = CuteParser.layout(when (part) { "S" -> source; "D" -> destination; "R" -> reference; else -> throw CuteException("operand") })
}

data class HardwareLayout(val layout: CuteLayout, val rows: Int, val columns: Int,
                          val threadLayout: CuteLayout? = null, val descriptor: Boolean = false, val tv: Boolean = true) {
    fun partition(thread: Int): List<Long> {
        cuteRequire(!descriptor && thread in 0 until layout.mode(0).size, "thread")
        return (0 until layout.mode(1).size).map { layout.index(layout.tvIndex(thread,it.toInt())) }
    }
}

private fun tile(vararg args: CuteOperand) = CuteOperand.Tile(args.toList())
private fun arg(a: CuteLayout) = CuteOperand.Layout(a)
private fun int(n: Long) = CuteOperand.Integer(n)
private val all = CuteOperand.All
private fun comp(a: CuteLayout, b: CuteOperand) = LayoutAlgebra.composition(a, b)
private fun zipDivide(a: CuteLayout, b: CuteOperand) = LayoutAlgebra.divideOrProduct(a, b, true, "zipped")
private fun reshape(a: CuteLayout, shape: IntTree) = LayoutAlgebra.composition(a, CuteLayout.compact(shape))

/** Direct translation of TiledMMA::thrfrg_{A,B,C} and get_layout{A,B,C}_TV. */
class CuteTiledMma(val atom: MmaAtom, threadLayout: CuteLayout, permutations: CuteOperand) {
    private val threads = CuteLayout.join(threadLayout.modes().also { cuteRequire(it.size <= 3, "mnk") } + List(3 - threadLayout.rank) { CuteLayout.scalar(1, 0) })
    val thrVMNK = LayoutAlgebra.divideOrProduct(CuteParser.layout(atom.thr), arg(threads), false, "tiled")
    private val perm = (permutations as? CuteOperand.Tile)?.values?.also { cuteRequire(it.size <= 3, "mnk") } ?: throw CuteException("mnk")
    private val effectivePerm = (perm + List(3 - perm.size) { all }).mapIndexed { i, p -> if (p == all) int(atom.shape[i] * thrVMNK.mode(i + 1).size) else p }
    val tileMNK = effectivePerm.map { p -> when (p) {
        is CuteOperand.Integer -> p.value
        is CuteOperand.Layout -> p.value.size
        else -> throw CuteException("mnk")
    }.also { cuteRequire(it > 0, "shape_positive") } }

    init {
        for (i in 0..2) cuteRequire(tileMNK[i] % (atom.shape[i] * threads.mode(i).size) == 0L, "mma_tile")
        for (p in effectivePerm) if (p is CuteOperand.Layout) dense(p.value)
        dense(threads)
    }

    fun layout(part: String): HardwareLayout {
        val axes = when (part) { "A" -> 0 to 2; "B" -> 1 to 2; "C" -> 0 to 1; else -> throw CuteException("operand") }
        val m = tileMNK[axes.first]; val n = tileMNK[axes.second]
        if (atom.descriptor(part)) {
            // GMMA SS operands live in shared memory; an ABLayout broadcast is NOT register ownership.
            return HardwareLayout(CuteLayout.compact(numbers(listOf(m,n))), m.toInt(), n.toInt(), thrVMNK, descriptor = true)
        }
        val ref = CuteLayout.compact(numbers(listOf(m,n)))
        val permuted = LayoutAlgebra.logical(ref, tile(effectivePerm[axes.first], effectivePerm[axes.second]), true)
        val atomTile = tile(int(atom.shape[axes.first]), int(atom.shape[axes.second]))
        val tiled = zipDivide(permuted, atomTile)
        val tv = comp(tiled, tile(arg(atom.layout(part)), all))
        var thrfrg = zipDivide(tv, tile(all, tile(int(thrVMNK.mode(axes.first + 1).size), int(thrVMNK.mode(axes.second + 1).size))))
        if (part != "C") {
            val broadcast = CuteLayout(numbers(listOf(thrVMNK.mode(1).size,thrVMNK.mode(2).size)), numbers(if (part == "A") listOf(1,0) else listOf(0,1)))
            thrfrg = comp(thrfrg, tile(tile(all, tile(arg(broadcast), all)), all))
        }
        val fullThr = CuteLayout.join(listOf(thrVMNK, LayoutAlgebra.complement(thrVMNK)))
        val logicalThr = CuteLayout(numbers(listOf(thrVMNK.size,1)), numbers(listOf(1,0)))
        val idx = LayoutAlgebra.composition(logicalThr, LayoutAlgebra.rightInverse(fullThr))
        return HardwareLayout(comp(thrfrg, tile(arg(idx), all)), m.toInt(), n.toInt(), thrVMNK)
    }
}

/** CuTe make_tiled_copy, make_tiled_copy_{A,B,C}, and source/destination atom remapping. */
class CuteTiledCopy(val atom: CopyAtom, val referenceTV: CuteLayout, val tiler: IntTree) {
    init {
        val ref = atom.layout("R")
        cuteRequire(referenceTV.rank == 2 && referenceTV.mode(0).size % ref.mode(0).size == 0L && referenceTV.mode(1).size % ref.mode(1).size == 0L, "copy_atom_size")
    }
    fun layout(part: String): HardwareLayout {
        val ref = atom.layout("R")
        val remap = LayoutAlgebra.composition(LayoutAlgebra.rightInverse(ref), atom.layout(part))
        val divided = zipDivide(referenceTV, tile(int(ref.mode(0).size), int(ref.mode(1).size)))
        val target = comp(divided, tile(arg(remap), all))
        val zipped = CuteLayout.join((0..1).map { j -> CuteLayout.join((0..1).map { i -> target.mode(i).mode(j) }) })
        val tv = LayoutAlgebra.coalesce(zipped, tile(int(1), tile(int(1), int(1))))
        val output = LayoutAlgebra.composition(CuteLayout.compact(tiler), tv)
        val rows = tiler.modes[0].size().toInt()
        return HardwareLayout(output, rows, (tiler.size() / rows).toInt())
    }
    /** CuTe retile_S/D both reinterpret a reference fragment; no data is moved. */
    fun retile(fragment: CuteLayout): CuteLayout {
        cuteRequire(fragment.rank == tiler.modes.size + 1, "retile_rank")
        val v = fragment.mode(0).size
        fun upcast(layout: CuteLayout, n: Long): CuteLayout {
            fun cast(s: IntTree, d: IntTree): Pair<IntTree,IntTree> {
                if (s is IntTree.Tuple) {
                    val children = s.children.zip((d as IntTree.Tuple).children).map { (a,b) -> cast(a,b) }
                    return tuple(children.map { it.first }) to tuple(children.map { it.second })
                }
                val stride = (d as IntTree.Num).value
                if (stride == 0L) return s to d
                cuteRequire(stride % n == 0L || n % stride == 0L, "divisibility")
                val factor = (n + stride - 1) / stride
                return IntTree.Num(((s as IntTree.Num).value + factor - 1) / factor) to IntTree.Num((stride + n - 1) / n)
            }
            val (s,d) = cast(layout.shape,layout.stride)
            return CuteLayout(s,d)
        }
        val frgMN = upcast(reshape(LayoutAlgebra.rightInverse(referenceTV),tiler), referenceTV.mode(0).size * v)
        val logical = LayoutAlgebra.logical(CuteLayout.scalar(v),arg(LayoutAlgebra.rightInverse(frgMN)),false)
        val frgV = zipDivide(logical,arg(CuteLayout.scalar(atom.layout("R").mode(1).size)))
        val extents = listOf(v) + frgMN.shape.modes.map { it.size() }
        for (i in extents.indices) cuteRequire(fragment.mode(i).size % extents[i] == 0L, "divisibility")
        val tiled = zipDivide(fragment,CuteOperand.Tile(extents.map(::int)))
        val value = comp(tiled,tile(arg(frgV),all))
        return CuteLayout.join(listOf(value.mode(0)) + value.mode(1).modes().drop(1))
    }
    companion object {
        fun make(atom: CopyAtom, threads: CuteLayout, values: CuteLayout): CuteTiledCopy {
            dense(threads); dense(values)
            val mn = LayoutAlgebra.orderedProduct(threads, values, true)
            val tv = reshape(LayoutAlgebra.rightInverse(mn), numbers(listOf(threads.size, values.size)))
            return CuteTiledCopy(atom, tv, tuple(mn.shape.modes.map { IntTree.Num(it.size()) }))
        }
        fun fromMma(atom: CopyAtom, mma: CuteTiledMma, part: String): CuteTiledCopy {
            val tv = mma.layout(part)
            cuteRequire(!tv.descriptor, "descriptor_copy")
            return CuteTiledCopy(atom, tv.layout, numbers(listOf(tv.rows.toLong(), tv.columns.toLong())))
        }
    }
}

private fun dense(layout: CuteLayout) {
    val seen = HashSet<Long>()
    for (i in 0 until layout.size) cuteRequire(layout.index(i) in 0 until layout.size && seen.add(layout.index(i)), "dense_layout")
}
