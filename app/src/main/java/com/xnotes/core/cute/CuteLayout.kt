package com.xnotes.core.cute

/** Bounded, static integer CuTe layouts. No Android or CUDA dependency on the device. */
class CuteException(val code: String, val detail: String = "") : IllegalArgumentException(detail.ifEmpty { code })

internal fun cuteRequire(ok: Boolean, code: String, detail: String = "") {
    if (!ok) throw CuteException(code, detail)
}

internal fun product(a: Long, b: Long): Long = try {
    Math.multiplyExact(a, b).also { cuteRequire(it in -MAX_NUMBER..MAX_NUMBER, "overflow") }
} catch (_: ArithmeticException) { throw CuteException("overflow") }

internal const val MAX_NUMBER = 1_000_000_000_000L
const val MAX_LAYOUT_SIZE = 65536L

sealed interface IntTree {
    data class Num(val value: Long) : IntTree { override fun toString() = value.toString() }
    data class Tuple(val children: List<IntTree>) : IntTree {
        init { cuteRequire(children.isNotEmpty() && children.size <= 32, "rank") }
        override fun toString() = children.joinToString(",", "(", ")")
    }
    val modes: List<IntTree> get() = if (this is Tuple) children else listOf(this)
    fun leaves(): List<Long> = when (this) {
        is Num -> listOf(value)
        is Tuple -> children.flatMap { it.leaves() }
    }
    fun size(): Long = when (this) {
        is Num -> value
        is Tuple -> children.fold(1L) { acc, t -> product(acc, t.size()) }
    }
    fun cpp(): String = when (this) {
        is Num -> "Int<$value>{}"
        is Tuple -> children.joinToString(",", "make_tuple(", ")") { it.cpp() }
    }
}

internal fun tuple(items: List<IntTree>) = IntTree.Tuple(items)
internal fun numbers(items: List<Long>): IntTree = if (items.size == 1) IntTree.Num(items.single()) else tuple(items.map { IntTree.Num(it) })

data class CuteLayout(val shape: IntTree, val stride: IntTree) {
    init {
        fun congruent(s: IntTree, d: IntTree): Boolean = when {
            s is IntTree.Num && d is IntTree.Num -> true
            s is IntTree.Tuple && d is IntTree.Tuple -> s.children.size == d.children.size && s.children.zip(d.children).all { (a, b) -> congruent(a, b) }
            else -> false
        }
        cuteRequire(congruent(shape, stride), "congruence")
        cuteRequire(shape.leaves().all { it > 0 }, "shape_positive")
        cuteRequire(shape.size() <= MAX_LAYOUT_SIZE, "size_limit")
        cuteRequire(stride.leaves().all { it in 0..1_000_000_000L }, "stride_range")
        cuteRequire(shape.leaves().size <= 64, "rank")
    }
    private val flatShape = shape.leaves()
    private val flatStride = stride.leaves()
    private val modeSizes = shape.modes.map { it.size() }
    val size: Long = shape.size()
    val rank: Int get() = shape.modes.size
    val depth: Int get() {
        fun depth(t: IntTree): Int = if (t is IntTree.Tuple) 1 + t.children.maxOf { depth(it) } else 0
        return depth(shape)
    }
    val cosize: Long get() = index(size - 1) + 1
    fun mode(i: Int): CuteLayout = CuteLayout(shape.modes[i], stride.modes[i])
    fun modes(): List<CuteLayout> = shape.modes.indices.map { mode(it) }

    /** CuTe's last mode extends beyond the domain; do not reduce it modulo its shape. */
    fun index(i: Long): Long {
        cuteRequire(i >= 0, "negative_coordinate")
        val ss = flatShape
        val ds = flatStride
        var rest = i
        var out = 0L
        for (k in ss.indices) {
            val c = if (k == ss.lastIndex) rest else rest % ss[k]
            out = Math.addExact(out, product(c, ds[k]))
            rest /= ss[k]
        }
        return out
    }

    fun coordinate(i: Long): IntTree {
        fun coord(s: IntTree, n: Long): IntTree = when (s) {
            is IntTree.Num -> IntTree.Num(n)
            is IntTree.Tuple -> {
                var rest = n
                tuple(s.children.mapIndexed { k, child ->
                    val c = if (k == s.children.lastIndex) rest else rest % child.size()
                    rest /= child.size()
                    coord(child, c)
                })
            }
        }
        return coord(shape, i)
    }

    /** T and V are natural colexicographic coordinates, irrespective of this layout's stride.
     * In particular, LayoutRight changes addresses; it never changes the numbering of T/V.
     */
    fun tvIndex(thread: Int, value: Int): Long {
        cuteRequire(rank == 2 && shape is IntTree.Tuple, "tv_rank")
        cuteRequire(thread.toLong() in 0 until modeSizes[0] && value.toLong() in 0 until modeSizes[1], "thread")
        return thread + modeSizes[0] * value
    }

    fun tvCoordinate(thread: Int, value: Int): IntTree {
        tvIndex(thread,value)
        return tuple(listOf(mode(0).coordinate(thread.toLong()),mode(1).coordinate(value.toLong())))
    }
    override fun toString() = "$shape:$stride"
    fun cpp() = "make_layout(${shape.cpp()},${stride.cpp()})"

    companion object {
        fun scalar(s: Long, d: Long = if (s == 1L) 0 else 1) = CuteLayout(IntTree.Num(s), IntTree.Num(d))
        fun join(layouts: List<CuteLayout>) = CuteLayout(tuple(layouts.map { it.shape }), tuple(layouts.map { it.stride }))
        fun compact(shape: IntTree, rightMajor: Boolean = false): CuteLayout {
            var stride = 1L
            fun visit(t: IntTree): IntTree = when (t) {
                is IntTree.Num -> IntTree.Num(if (t.value == 1L) 0 else stride).also { stride = product(stride, t.value) }
                is IntTree.Tuple -> if (rightMajor) tuple(t.children.reversed().map { visit(it) }.reversed()) else tuple(t.children.map { visit(it) })
            }
            return CuteLayout(shape, visit(shape))
        }
    }
}

sealed interface CuteOperand {
    data class Layout(val value: CuteLayout) : CuteOperand
    data class Tile(val values: List<CuteOperand>) : CuteOperand
    data class Integer(val value: Long) : CuteOperand
    data object All : CuteOperand
    fun cpp(): String = when (this) {
        is Layout -> value.cpp()
        is Tile -> values.joinToString(",", "make_tile(", ")") { it.cpp() }
        is Integer -> "Int<$value>{}"
        All -> "_"
    }
}

/** Accepts CuTe's printed Shape:Stride, including _4, and tile(4,8) or (4,8) tilers. */
object CuteParser {
    fun layout(text: String): CuteLayout = when (val operand = Parser(text).parse()) {
        is CuteOperand.Layout -> operand.value
        is CuteOperand.Integer -> CuteLayout.scalar(operand.value)
        is CuteOperand.Tile -> CuteLayout.compact(tree(operand))
        CuteOperand.All -> throw CuteException("layout_expected")
    }
    fun operand(text: String): CuteOperand = Parser(text).parse()
    fun intTree(text: String): IntTree = tree(operand(text))
    private fun tree(o: CuteOperand): IntTree = when (o) {
        is CuteOperand.Integer -> IntTree.Num(o.value)
        is CuteOperand.Tile -> tuple(o.values.map { tree(it) })
        else -> throw CuteException("integer_tuple")
    }
    private class Parser(val text: String) {
        var pos = 0
        init { cuteRequire(text.length <= 2048, "input_limit") }
        fun space() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun take(c: Char): Boolean { space(); return (pos < text.length && text[pos] == c).also { if (it) pos++ } }
        fun parse(): CuteOperand {
            val result = read(0)
            space()
            cuteRequire(pos == text.length, "syntax", "${pos + 1}")
            return result
        }
        fun read(depth: Int): CuteOperand {
            cuteRequire(depth <= 8, "depth")
            space()
            val explicitTile = text.startsWith("tile", pos)
            if (explicitTile) pos += 4
            var result: CuteOperand
            if (take('(')) {
                val children = mutableListOf<CuteOperand>()
                do { children += read(depth + 1); cuteRequire(children.size <= 32, "rank") } while (take(','))
                cuteRequire(take(')'), "syntax", "${pos + 1}")
                result = CuteOperand.Tile(children)
            } else {
                cuteRequire(!explicitTile, "syntax", "${pos + 1}")
                space()
                val under = take('_')
                val start = pos
                if (pos < text.length && text[pos] == '-') pos++
                while (pos < text.length && text[pos].isDigit()) pos++
                result = if (pos == start && under) CuteOperand.All else {
                    val n = text.substring(start, pos).toLongOrNull() ?: throw CuteException("syntax", "${start + 1}")
                    cuteRequire(n in -1_000_000_000L..1_000_000_000L, "overflow")
                    CuteOperand.Integer(n)
                }
            }
            if (take(':')) result = CuteOperand.Layout(CuteLayout(tree(result), tree(read(depth + 1))))
            return result
        }
    }
}
