package com.xnotes.core.cute

enum class CuteWorkspace { ALGEBRA, MMA, COPY, TMA }
enum class CuteCopySource { ATOM, TILED, MMA_A, MMA_B, MMA_C }

data class CuteHardwareSpec(
    val mmaAtom: String = "sm80_f16",
    val mmaThreads: String = "(2,2,1):(1,2,0)",
    val permutation: String = "(_,_,_)",
    val operand: String = "C",
    val copyAtom: String = "ldsm_n4",
    val copySource: CuteCopySource = CuteCopySource.TILED,
    val copyThreads: String = "(8,4):(4,1)",
    val copyValues: String = "(1,8):(0,1)",
    val copySide: String = "D",
    val thread: Int = -1,
    val retile: Boolean = false,
    val fragment: String = "(8,2,2):(1,8,16)",
) {
    fun mma() = CuteTiledMma(CuteAtoms.mma(mmaAtom), CuteParser.layout(mmaThreads), CuteParser.operand(permutation))
    fun copy(): CuteTiledCopy {
        val atom = CuteAtoms.copy(copyAtom)
        return when (copySource) {
            CuteCopySource.ATOM -> throw CuteException("operand")
            CuteCopySource.TILED -> CuteTiledCopy.make(atom, CuteParser.layout(copyThreads), CuteParser.layout(copyValues))
            else -> CuteTiledCopy.fromMma(atom, mma(), copySource.name.takeLast(1))
        }
    }
    fun view(workspace: CuteWorkspace): HardwareLayout = if (workspace == CuteWorkspace.MMA) mma().layout(operand) else {
        if (copySource == CuteCopySource.ATOM) {
            val layout = CuteAtoms.copy(copyAtom).layout(copySide)
            val extent = layout.cosize.toInt()
            val rows = if (extent % 16 == 0) 16 else extent
            HardwareLayout(layout, rows, extent / rows)
        } else if (retile) {
            val layout = copy().retile(CuteParser.layout(fragment))
            HardwareLayout(layout,layout.mode(0).size.toInt(),(layout.size / layout.mode(0).size).toInt(),tv = false)
        } else copy().layout(copySide)
    }
    fun mmaCpp() = "auto mma = make_tiled_mma(${CuteAtoms.mma(mmaAtom).cppType}{}, ${CuteParser.layout(mmaThreads).cpp()}, ${CuteParser.operand(permutation).cpp()});\n"
    fun cpp(workspace: CuteWorkspace): String = buildString {
        append("#include <cute/tensor.hpp>\n#include <cute/atom/mma_atom.hpp>\n#include <cute/atom/copy_atom.hpp>\nusing namespace cute;\n// Function body:\n")
        if (workspace == CuteWorkspace.MMA) {
            append(mmaCpp())
            if (CuteAtoms.mma(mmaAtom).descriptor(operand)) {
                append("// $operand is a GMMA shared-memory descriptor operand, not a register TV fragment.\n")
                append("print(mma);\n")
            } else append("auto r = mma.get_layout${operand}_TV();\nprint(r);\n")
        } else {
            val atom = CuteAtoms.copy(copyAtom)
            append("using Atom = ${atom.cppType};\n")
            if (copySource == CuteCopySource.ATOM) {
                append("auto r = Atom::ValLayout${when(copySide) { "S" -> "Src"; "D" -> "Dst"; else -> "Ref" }}{};\nprint(r);\n")
            } else {
                if (copySource != CuteCopySource.TILED) append(mmaCpp())
                append("auto copy = ")
                if (copySource == CuteCopySource.TILED) append("make_tiled_copy(Atom{}, ${CuteParser.layout(copyThreads).cpp()}, ${CuteParser.layout(copyValues).cpp()});\n")
                else append("make_tiled_copy_${copySource.name.takeLast(1)}(Atom{}, mma);\n")
                if (retile) {
                    append("auto fragment = make_tensor(counting_iterator<int>{}, ${CuteParser.layout(fragment).cpp()});\n")
                    append("auto r = copy.get_slice(0).retile_${copySide}(fragment);\nprint(r.layout());\n")
                } else append("auto r = copy.get_layout${copySide}_TV();\nprint(r);\n")
            }
        }
        append("// Display uses element offsets, decoded as m + M*n.\n")
    }
}

internal fun CuteSpec.hardwareEvaluation(): CuteEvaluation {
    if (workspace == CuteWorkspace.TMA) {
        val analysis = tma.analyze()
        return CuteEvaluation(analysis.layout, analysis.layout, this, tmaAnalysis = analysis)
    }
    val view = hardware.view(workspace)
    return CuteEvaluation(view.layout, view.layout, this, hardwareLayout = view)
}
