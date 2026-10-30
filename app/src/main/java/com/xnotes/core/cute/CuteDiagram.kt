package com.xnotes.core.cute

import com.xnotes.core.geometry.Rect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64

/** The editable source travels inside the SVG asset, including through copy, save and reopen. */
data class CuteSpec(
    val a: String = "((4,8),(2,2)):((32,1),(16,8))",
    val operation: CuteOperation = CuteOperation.LAYOUT,
    val b: String = "",
    val tv: Boolean = true,
    val tileM: Int = 16,
    val tileN: Int = 8,
    // Optional codomain decoder for custom algebra diagrams, NEVER the T/V domain order.
    // Native MMA/Copy views always use the CuTe LayoutLeft codomain.
    val rowMajor: Boolean = false,
    val showInput: Boolean = false,
    val rowStart: Int = 0,
    val columnStart: Int = 0,
    val workspace: CuteWorkspace = CuteWorkspace.ALGEBRA,
    val swizzle: CuteSwizzle? = null,
    val hardware: CuteHardwareSpec = CuteHardwareSpec(),
    val tma: CuteTmaSpec = CuteTmaSpec(),
    /** Presentation only: GEMM shows B as K×N, while native CuTe B coordinates remain N×K. */
    val transposeDiagram: Boolean = false,
) {
    fun evaluate(): CuteEvaluation {
        if (workspace != CuteWorkspace.ALGEBRA) return hardwareEvaluation()
        swizzle?.validate()
        val input = CuteParser.layout(a)
        return CuteEvaluation(input, operation.apply(input, b), this)
    }

    fun cpp(): String = buildString {
        if (workspace == CuteWorkspace.TMA) { append(tma.analyze().cpp()); return@buildString }
        if (workspace != CuteWorkspace.ALGEBRA) { append(hardware.cpp(workspace)); return@buildString }
        append("using namespace cute;\nauto a = ${CuteParser.layout(a).cpp()};\n")
        if (operation == CuteOperation.LAYOUT) append("auto r = a;\n") else {
            val arg = when {
                operation == CuteOperation.COMPLEMENT && b.isNotBlank() -> CuteParser.intTree(b).cpp()
                operation in listOf(CuteOperation.BLOCKED_PRODUCT, CuteOperation.RAKED_PRODUCT) -> CuteParser.layout(b).cpp()
                b.isNotBlank() && (operation.arity == 2 || operation == CuteOperation.COALESCE) -> CuteParser.operand(b).cpp()
                else -> null
            }
            if (arg != null) append("auto b = $arg;\n")
            append("auto r = ${operation.id}(a${if (arg != null) ", b" else ""});\n")
        }
        append("print(r);\n")
        swizzle?.let {
            it.validate()
            append("auto swizzled = composition(${it.cpp()}, Int<${it.offset}>{}, r);\nprint(swizzled);\n")
        }
    }
}

data class CuteEvaluation(val input: CuteLayout, val result: CuteLayout, val spec: CuteSpec,
                          val hardwareLayout: HardwareLayout? = null, val tmaAnalysis: CuteTmaAnalysis? = null) {
    val shown get() = if (spec.workspace == CuteWorkspace.ALGEBRA && spec.showInput) input else result
    val viewSpec get() = when {
        hardwareLayout != null -> spec.copy(tv = hardwareLayout.tv && !hardwareLayout.descriptor, tileM = hardwareLayout.rows, tileN = hardwareLayout.columns, rowMajor = false)
        tmaAnalysis != null -> spec.copy(tv = false)
        else -> spec
    }
    fun offset(index: Long): Long = tmaAnalysis?.element(index)?.sharedByte ?: shown.index(index).let {
        if (spec.workspace == CuteWorkspace.ALGEBRA && !spec.showInput) spec.swizzle?.map(it) ?: it else it
    }
    fun grid() = CuteGrid(shown, viewSpec, ::offset, tmaAnalysis)
    val title get() = when (spec.workspace) {
        CuteWorkspace.ALGEBRA -> spec.operation.id + if (spec.swizzle != null && !spec.showInput) " / ${spec.swizzle}" else ""
        CuteWorkspace.MMA -> "TiledMMA / ${spec.hardware.operand}"
        CuteWorkspace.COPY -> "${if(spec.hardware.copySource == CuteCopySource.ATOM) "Copy Atom" else if(spec.hardware.retile) "retile" else "TiledCopy"} / ${spec.hardware.copySide}"
        CuteWorkspace.TMA -> "TMA / ${spec.tma.swizzleBytes} B swizzle / SMEM byte offsets"
    }
    fun sourceLines(): List<String> = buildList {
        when (spec.workspace) {
            CuteWorkspace.ALGEBRA -> {
                add("A = $input")
                if (spec.operation != CuteOperation.LAYOUT) {
                    if (spec.b.isNotBlank()) add("B = ${spec.b}")
                    add("R = $result")
                }
                if (spec.swizzle != null && !spec.showInput) add("Element offsets: ${spec.swizzle} o R")
            }
            CuteWorkspace.MMA -> {
                add(CuteAtoms.mma(spec.hardware.mmaAtom).cppType)
                add("AtomLayoutMNK = ${spec.hardware.mmaThreads}; PermutationMNK = ${spec.hardware.permutation}")
                add("R = $result")
                if (hardwareLayout?.descriptor == true) add("Shared-memory descriptor operand: logical coordinates only; no register TV ownership")
            }
            CuteWorkspace.COPY -> {
                add(CuteAtoms.copy(spec.hardware.copyAtom).cppType)
                add(when (spec.hardware.copySource) {
                    CuteCopySource.TILED -> "make_tiled_copy; T=${spec.hardware.copyThreads}; V=${spec.hardware.copyValues}"
                    CuteCopySource.ATOM -> "Copy_Atom::ValLayout${when (spec.hardware.copySide) { "S" -> "Src"; "D" -> "Dst"; else -> "Ref" }}"
                    else -> "make_tiled_copy_${spec.hardware.copySource.name.takeLast(1)}"
                })
                if (spec.hardware.copySource.name.startsWith("MMA")) {
                    add(CuteAtoms.mma(spec.hardware.mmaAtom).cppType)
                    add("AtomLayoutMNK = ${spec.hardware.mmaThreads}; PermutationMNK = ${spec.hardware.permutation}")
                }
                add("R = $result")
                if (spec.hardware.retile && spec.hardware.copySource != CuteCopySource.ATOM) add("Reference fragment = ${spec.hardware.fragment}; offsets within fragment storage")
            }
            CuteWorkspace.TMA -> {
                val t = tmaAnalysis!!
                add("GMEM shape=${spec.tma.globalShape}; outer byte strides=${spec.tma.globalStrides}")
                add("Box=${spec.tma.box}; origin=${spec.tma.origin}; steps=${spec.tma.steps}; element=${spec.tma.elementBytes} B")
                add("GMEM base=${spec.tma.globalBase} B; SMEM base=${spec.tma.sharedBase} B; pitch=${t.rowPitchBytes} B")
                add("SMEM allocation=${t.allocationBytes} B; payload=${t.transferBytes} B; interleave NONE")
                add("B = starting 4-byte bank (0..31); layout analysis, no GPU execution")
            }
        }
        if (spec.transposeDiagram) add("Display axes transposed; native CuTe coordinates unchanged")
    }
}

data class CuteOwner(val thread: Int, val value: Int, val index: Long)
data class CuteCell(val row: Int, val column: Int, val offset: Long?, val index: Long?, val owners: List<CuteOwner>)

/** Mirrors print_latex / print_latex_tv: mode-0 runs down, mode-1 runs right. */
class CuteGrid(val layout: CuteLayout, val spec: CuteSpec, private val map: (Long) -> Long = layout::index,
               private val tma: CuteTmaAnalysis? = null) {
    private val layoutRows: Int
    private val layoutColumns: Int
    val rows: Int get() = if (spec.transposeDiagram) layoutColumns else layoutRows
    val columns: Int get() = if (spec.transposeDiagram) layoutRows else layoutColumns
    private val owners: Map<Long, List<CuteOwner>>
    val aliases: Int
    val holes: Long
    init {
        if (spec.tv) {
            cuteRequire(layout.rank == 2 && layout.shape is IntTree.Tuple, "tv_rank")
            cuteRequire(spec.tileM > 0 && spec.tileN > 0 && spec.tileM.toLong() * spec.tileN <= MAX_LAYOUT_SIZE, "tile_size")
            layoutRows = spec.tileM; layoutColumns = spec.tileN
            val table = HashMap<Long, MutableList<CuteOwner>>()
            val threads = layout.mode(0).size.toInt(); val values = layout.mode(1).size.toInt()
            for (t in 0 until threads) for (v in 0 until values) {
                val i = layout.tvIndex(t,v)
                val address = map(i)
                cuteRequire(address in 0 until layoutRows.toLong() * layoutColumns, "tv_outside", address.toString())
                table.getOrPut(address) { ArrayList() }.add(CuteOwner(t, v, i))
            }
            owners = table
            aliases = layout.size.toInt() - table.size
            holes = layoutRows.toLong() * layoutColumns - table.size
        } else {
            layoutRows = layout.mode(0).size.toInt()
            layoutColumns = (layout.size / layoutRows).toInt()
            owners = emptyMap()
            val unique = HashSet<Long>()
            for (i in 0 until layout.size) unique.add(map(i))
            aliases = layout.size.toInt() - unique.size
            holes = if (tma != null) 0 else (unique.maxOrNull() ?: -1) + 1 - unique.size
        }
    }
    val rowStart = spec.rowStart.coerceIn(0, rows - 1)
    val columnStart = spec.columnStart.coerceIn(0, columns - 1)
    val rowEnd = minOf(rows, rowStart + WINDOW)
    val columnEnd = minOf(columns, columnStart + WINDOW)
    val windowed = rows > WINDOW || columns > WINDOW
    fun cell(row: Int, column: Int): CuteCell {
        val m = if (spec.transposeDiagram) column else row
        val n = if (spec.transposeDiagram) row else column
        if (spec.tv) {
            val address = if (spec.rowMajor) m.toLong() * layoutColumns + n else m + layoutRows.toLong() * n
            val owned = owners[address].orEmpty()
            return CuteCell(row, column, address, owned.firstOrNull()?.index, owned)
        }
        val i = m + layoutRows.toLong() * n
        return CuteCell(row, column, map(i), i, emptyList())
    }
    fun labels(cell: CuteCell): List<String> = if (spec.tv) {
        val first = cell.owners.firstOrNull()
        if (first == null) listOf("—") else listOf("T${first.thread}", "V${first.value}${if (cell.owners.size > 1) "+" else ""}")
    } else if (tma != null) listOf(cell.offset.toString(), "B${tma.element(cell.index!!).bank}") else listOf(cell.offset.toString())
    fun color(cell: CuteCell): String = if (spec.tv) {
        cell.owners.firstOrNull()?.let { TV_COLORS[it.thread % 8] } ?: "#FFFFFF"
    } else if (tma != null) TV_COLORS[tma.element(cell.index!!).bank % 8] else BW_COLORS[(cell.offset!! % 8).toInt()]

    companion object {
        const val WINDOW = 32
        // NVIDIA TikzColor_TV and TikzColor_BWx8, BSD-3-Clause. See assets/licenses/cutlass-cute.txt.
        val TV_COLORS = listOf("#AFAFFF", "#AFFFAF", "#FFFFAF", "#FFAFAF", "#D2D2FF", "#D2FFD2", "#FFFFD2", "#FFD2D2")
        val BW_COLORS = listOf("#FFFFFF", "#999999", "#CCCCCC", "#666666", "#E6E6E6", "#808080", "#B3B3B3", "#4D4D4D")
    }
}

/** SVG pixels and the exact matrix bounds, so separately editable diagrams can align their grids. */
data class CuteDiagramAsset(val spec: CuteSpec, val svg: String, val width: Int, val height: Int, val gridRect: Rect)

object CuteDiagram {
    private const val OPEN = "<metadata id=\"xnotes-cute-v1\">"
    private const val CLOSE = "</metadata>"
    const val REFERENCE = "CUTLASS dc45f979ae33"

    fun encode(spec: CuteSpec): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(if (spec.transposeDiagram) 3 else 2); out.writeUTF(spec.a); out.writeUTF(spec.operation.name); out.writeUTF(spec.b)
            out.writeBoolean(spec.tv); out.writeInt(spec.tileM); out.writeInt(spec.tileN); out.writeBoolean(spec.rowMajor)
            out.writeBoolean(spec.showInput); out.writeInt(spec.rowStart); out.writeInt(spec.columnStart)
            out.writeUTF(spec.workspace.name)
            out.writeBoolean(spec.swizzle != null)
            spec.swizzle?.let { out.writeInt(it.bits); out.writeInt(it.base); out.writeInt(it.shift); out.writeLong(it.offset) }
            spec.hardware.let {
                listOf(it.mmaAtom,it.mmaThreads,it.permutation,it.operand,it.copyAtom,it.copySource.name,it.copyThreads,it.copyValues,it.copySide).forEach(out::writeUTF)
                out.writeInt(it.thread)
                out.writeBoolean(it.retile); out.writeUTF(it.fragment)
            }
            spec.tma.let {
                listOf(it.globalShape,it.globalStrides,it.box,it.origin,it.steps).forEach(out::writeUTF)
                out.writeInt(it.elementBytes); out.writeInt(it.swizzleBytes); out.writeLong(it.globalBase); out.writeLong(it.sharedBase)
            }
            if (spec.transposeDiagram) out.writeBoolean(true)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also { cuteRequire(it.length <= 48000, "input_limit") }
    }

    fun decode(encoded: String): CuteSpec? = runCatching {
        cuteRequire(encoded.length <= 48000, "input_limit")
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val version = input.readInt()
            cuteRequire(version in 1..3, "version")
            fun text() = input.readUTF().also { cuteRequire(it.length <= 2048, "input_limit") }
            var s = CuteSpec(text(), CuteOperation.valueOf(text()), text(), input.readBoolean(), input.readInt(), input.readInt(), input.readBoolean(), input.readBoolean(), input.readInt(), input.readInt())
            if (version >= 2) {
                val workspace = CuteWorkspace.valueOf(text())
                val swizzle = if (input.readBoolean()) CuteSwizzle(input.readInt(),input.readInt(),input.readInt(),input.readLong()) else null
                val hardware = CuteHardwareSpec(text(),text(),text(),text(),text(),CuteCopySource.valueOf(text()),text(),text(),text(),input.readInt(),input.readBoolean(),text())
                val tma = CuteTmaSpec(text(),text(),text(),text(),text(),input.readInt(),input.readInt(),input.readLong(),input.readLong())
                s = s.copy(workspace = workspace, swizzle = swizzle, hardware = hardware, tma = tma)
            }
            if (version >= 3) s = s.copy(transposeDiagram = input.readBoolean())
            s
        }
    }.getOrNull()

    fun read(file: File): CuteSpec? = runCatching {
        val head = file.inputStream().use {
            val bytes = ByteArray(65536)
            var count = 0
            while (count < bytes.size) {
                val n = it.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            String(bytes, 0, count, Charsets.UTF_8)
        }
        val start = head.indexOf(OPEN)
        if (start < 0) null else {
            val end = head.indexOf(CLOSE, start)
            if (end < 0) null else decode(head.substring(start + OPEN.length, end))
        }
    }.getOrNull()

    /** Vector SVG and editable metadata in a single existing ImageItem asset. */
    fun svg(e: CuteEvaluation): String = render(e).svg

    fun render(e: CuteEvaluation): CuteDiagramAsset {
        val grid = e.grid()
        val cell = 56
        val left = 56
        val lines = buildList {
            addAll(e.sourceLines())
            if (grid.spec.tv && grid.spec.transposeDiagram) add("(T,V) -> native tile (${grid.spec.tileM} x ${grid.spec.tileN}) -> offset -> transposed display (${grid.rows} x ${grid.columns})")
            else if (grid.spec.tv) add("(T,V) -> shape (LayoutLeft) -> offset -> (m,n) | ${grid.rows} x ${grid.columns} | offset = ${if (grid.spec.rowMajor) "m*N+n (custom decode)" else "m+M*n"}")
            else add("mode 0 (down) / remaining modes (right) | size=${e.shown.size} | cosize=${e.shown.cosize}")
            if (grid.windowed) add("Window: rows ${grid.rowStart}..${grid.rowEnd - 1}, cols ${grid.columnStart}..${grid.columnEnd - 1}")
            if (grid.aliases > 0) add("${grid.aliases} repeated offsets; TV shows first owner (+)")
        }.flatMap { (if (it.length > 600) it.take(597) + "..." else it).chunked(94) }
        val top = 76 + lines.size * 20
        val width = maxOf(640, left + (grid.columnEnd - grid.columnStart) * cell + 28, (lines.maxOfOrNull { it.length } ?: 0) * 9 + 40)
        val height = top + (grid.rowEnd - grid.rowStart) * cell + 48
        fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">")
            append(OPEN).append(encode(e.spec)).append(CLOSE)
            append("<rect width=\"$width\" height=\"$height\" fill=\"white\"/>")
            fun text(x: Int, y: Int, value: String, size: Int = 15, center: Boolean = false) {
                append("<text x=\"$x\" y=\"$y\" font-family=\"monospace\" font-size=\"$size\" fill=\"#111111\"${if (center) " text-anchor=\"middle\"" else ""}>${xml(value)}</text>")
            }
            text(20, 30, "CuTe / ${e.title}${if (grid.spec.tv) " / TV Layout" else ""}", 20)
            lines.forEachIndexed { i, s -> text(20, 54 + i * 20, s) }
            for (m in grid.rowStart until grid.rowEnd) {
                val y = top + (m - grid.rowStart) * cell
                text(left - 20, y + cell / 2 + 5, m.toString(), 16, true)
                for (n in grid.columnStart until grid.columnEnd) {
                    val x = left + (n - grid.columnStart) * cell
                    val c = grid.cell(m, n)
                    append("<rect x=\"$x\" y=\"$y\" width=\"$cell\" height=\"$cell\" fill=\"${grid.color(c)}\" stroke=\"black\" stroke-width=\"1\"/>")
                    val labels = grid.labels(c)
                    labels.forEachIndexed { i, label -> text(x + cell / 2, y + cell / 2 + 5 + (i * 18 - if (labels.size == 2) 9 else 0), label, minOf(15, (48f / (label.length * 0.62f)).toInt()), true) }
                }
            }
            for (n in grid.columnStart until grid.columnEnd) text(left + (n - grid.columnStart) * cell + cell / 2, top - 12, n.toString(), 16, true)
            text(20, height - 16, "CuTe Layout algebra | CUTLASS", 12)
            append("</svg>")
        }
        return CuteDiagramAsset(e.spec, svg, width, height, Rect(left.toDouble(), top.toDouble(),
            ((grid.columnEnd - grid.columnStart) * cell).toDouble(), ((grid.rowEnd - grid.rowStart) * cell).toDouble()))
    }
}

data class CutePreset(val name: String, val spec: CuteSpec)
object CutePresets {
    val all = listOf(
        CutePreset("TiledMMA · SM80 · 128 threads", CuteSpec(workspace = CuteWorkspace.MMA)),
        CutePreset("MMA Atom · SM80", CuteSpec(workspace = CuteWorkspace.MMA, hardware = CuteHardwareSpec(mmaThreads = "(1,1,1):(0,0,0)"))),
        CutePreset("TiledCopy · LDSM x4", CuteSpec(workspace = CuteWorkspace.COPY)),
        CutePreset("Copy → MMA A · LDSM x4", CuteSpec(workspace = CuteWorkspace.COPY, hardware = CuteHardwareSpec(copySource = CuteCopySource.MMA_A))),
        CutePreset("Copy Atom · cp.async 128b", CuteSpec(workspace = CuteWorkspace.COPY, hardware = CuteHardwareSpec(copyAtom = "cp_async_ca", copySource = CuteCopySource.ATOM))),
        CutePreset("retile_D · LDSM x4", CuteSpec(workspace = CuteWorkspace.COPY, hardware = CuteHardwareSpec(retile = true))),
        CutePreset("GMMA SS · shared descriptor A", CuteSpec(workspace = CuteWorkspace.MMA, hardware = CuteHardwareSpec(mmaAtom = "sm90_gmma_ss", mmaThreads = "(1,1,1):(0,0,0)", operand = "A"))),
        CutePreset("Swizzle<3,3,3> · 8×64", CuteSpec(a = "(8,64):(64,1)", tv = false, swizzle = CuteSwizzle())),
        CutePreset("TMA · FP16 · 128 B swizzle", CuteSpec(workspace = CuteWorkspace.TMA)),
        CutePreset("TMA · 3D · OOB", CuteSpec(workspace = CuteWorkspace.TMA, tma = CuteTmaSpec(globalShape = "(64,16,4)", globalStrides = "(128,2048)", box = "(32,4,2)", origin = "(48,14,3)", steps = "(1,1,1)", swizzleBytes = 64))),
        CutePreset("SM80 MMA · C (16×8)", CuteSpec()),
        CutePreset("SM80 MMA · A (16×16)", CuteSpec(a = "((4,8),(2,2,2)):((32,1),(16,8,128))", tileN = 16)),
        CutePreset("SM80 MMA · B (8×16)", CuteSpec(a = "((4,8),(2,2)):((16,1),(8,64))", tileM = 8, tileN = 16)),
        CutePreset("composition", CuteSpec("(6,2):(8,2)", CuteOperation.COMPOSITION, "(4,3):(3,1)", tv = false)),
        CutePreset("coalesce", CuteSpec("(2,(1,6)):(1,(6,2))", CuteOperation.COALESCE, tv = false)),
        CutePreset("zipped_divide", CuteSpec("(8,8):(8,1)", CuteOperation.ZIPPED_DIVIDE, "tile(4,2)", tv = false)),
        CutePreset("blocked_product", CuteSpec("(2,5):(5,1)", CuteOperation.BLOCKED_PRODUCT, "(3,4):(1,3)", tv = false)),
        CutePreset("raked_product", CuteSpec("(2,5):(5,1)", CuteOperation.RAKED_PRODUCT, "(3,4):(1,3)", tv = false)),
        CutePreset("complement", CuteSpec("4:2", CuteOperation.COMPLEMENT, "24", tv = false)),
        CutePreset("row-major", CuteSpec("(8,8):(8,1)", tv = false)),
        CutePreset("broadcast", CuteSpec("(4,8):(0,1)", tv = false)),
    )
}
