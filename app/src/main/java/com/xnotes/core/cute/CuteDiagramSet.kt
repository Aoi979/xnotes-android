package com.xnotes.core.cute

import com.xnotes.core.geometry.Rect

data class CuteDiagramPlacement(val asset: CuteDiagramAsset, val rect: Rect) {
    val gridRect: Rect get() = asset.gridRect.translate(rect.x, rect.y)
}

/** Render independent, editable assets at one common cell scale before fitting them to the view. */
object CuteDiagramSet {
    private const val GAP = 64.0

    fun create(spec: CuteSpec): List<CuteDiagramPlacement> {
        val base = spec.copy(showInput = false, rowStart = 0, columnStart = 0, transposeDiagram = false)
        return when (base.workspace) {
            CuteWorkspace.MMA -> {
                fun operand(part: String) = CuteDiagram.render(base.copy(
                    hardware = base.hardware.copy(operand = part, thread = -1),
                    transposeDiagram = part == "B",
                ).evaluate())
                val a = operand("A")
                val b = operand("B")
                val c = operand("C")
                val gridTop = maxOf(a.gridRect.top, c.gridRect.top)
                val aRect = Rect(0.0, gridTop - a.gridRect.top, a.width.toDouble(), a.height.toDouble())
                val cRect = Rect(aRect.right + GAP, gridTop - c.gridRect.top, c.width.toDouble(), c.height.toDouble())
                val bRect = Rect(cRect.left + c.gridRect.left - b.gridRect.left,
                    minOf(aRect.top, cRect.top) - GAP - b.height, b.width.toDouble(), b.height.toDouble())
                listOf(CuteDiagramPlacement(a, aRect), CuteDiagramPlacement(b, bRect), CuteDiagramPlacement(c, cRect))
            }
            CuteWorkspace.COPY -> {
                val assets = listOf("S", "D").map { side ->
                    CuteDiagram.render(base.copy(hardware = base.hardware.copy(copySide = side, thread = -1)).evaluate())
                }
                val gridTop = assets.maxOf { it.gridRect.top }
                var x = 0.0
                assets.map { asset ->
                    CuteDiagramPlacement(asset, Rect(x, gridTop - asset.gridRect.top, asset.width.toDouble(), asset.height.toDouble()))
                        .also { x = it.rect.right + GAP }
                }
            }
            else -> throw CuteException("operand")
        }
    }
}
