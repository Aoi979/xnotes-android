package com.xnotes.core.math

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import kotlin.math.abs

/** Row-major matrix in mathematical coordinates (positive y points up). */
data class Matrix2(val a: Double, val b: Double, val c: Double, val d: Double) {
    val determinant: Double get() = a * d - b * c
    val valid: Boolean get() = listOf(a, b, c, d).all { it.isFinite() && abs(it) <= MAX_COEFFICIENT }
    fun apply(p: Pt): Pt = Pt(a * p.x + b * p.y, c * p.x + d * p.y)

    /** Conjugate by the screen's y reflection; [origin] stays fixed, even far from (0, 0). */
    fun onCanvas(origin: Pt): Affine = Affine(
        a, -c, -b, d,
        origin.x - a * origin.x + b * origin.y,
        origin.y + c * origin.x - d * origin.y,
    )

    companion object {
        const val MAX_COEFFICIENT = 20.0
        val IDENTITY = Matrix2(1.0, 0.0, 0.0, 1.0)
        val ROTATE = Matrix2(0.0, -1.0, 1.0, 0.0)
        val SHEAR = Matrix2(1.0, 1.0, 0.0, 1.0)
        val REFLECT = Matrix2(1.0, 0.0, 0.0, -1.0)
        val PROJECT = Matrix2(1.0, 0.0, 0.0, 0.0)
    }
}

/** Geometry operations for handwritten ink; photographs, text and glued connectors are excluded. */
object LinearInk {
    fun supports(items: List<CanvasItem>): Boolean = items.isNotEmpty() &&
        items.all { !it.locked && (it is Stroke || it is ShapeItem) }

    /** For detached preview/copy geometry: avoid allocating two unused history snapshots. */
    fun mapDetached(items: List<CanvasItem>, matrix: Matrix2, origin: Pt) {
        require(matrix.valid && origin.x.isFinite() && origin.y.isFinite())
        require(supports(items))
        val transform = matrix.onCanvas(origin)
        items.forEach {
            if (it is Stroke) it.applyTransform(transform, scaleWidth = false) else it.applyTransform(transform)
        }
    }

    /** An already-applied edit, ready for history. Width is preserved even for rank-zero maps. */
    fun apply(items: List<CanvasItem>, matrix: Matrix2, origin: Pt): TransformItems {
        require(matrix.valid && origin.x.isFinite() && origin.y.isFinite())
        require(supports(items))
        val before = items.map { it.snapshotGeometry() }
        try {
            mapDetached(items, matrix, origin)
        } catch (error: Exception) {
            items.forEachIndexed { index, item -> item.restoreGeometry(before[index]) }
            throw error
        }
        return TransformItems(items, before, items.map { it.snapshotGeometry() })
    }
}
