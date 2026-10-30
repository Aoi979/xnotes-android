package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.hypot

/** Only user navigation feeds this state; document edits and automatic camera moves do not. */
internal class ToolbarMotion {
    enum class Axis { VERTICAL, HORIZONTAL, FREE }

    var expanded by mutableStateOf(true)
        private set
    var enabled = false
    var allowAutoCollapse = true
    /** Set from FloatingToolbarDefaults.ScrollDistanceThreshold in the mounted toolbar. */
    var thresholdDp = 40.0
    private var axis: Axis? = null
    private var x = 0.0
    private var y = 0.0
    private var reverseTravel = 0.0
    private var directionX = 0.0
    private var directionY = 0.0

    fun reset(expand: Boolean = true) {
        expanded = expand
        x = 0.0
        y = 0.0
        reverseTravel = 0.0
        directionX = 0.0
        directionY = 0.0
        axis = null
    }

    fun toggle() = reset(!expanded)

    /** Finger/content translation in viewport pixels, independent of document zoom. */
    fun pan(dx: Double, dy: Double, axis: Axis, density: Float) {
        if (!enabled || !allowAutoCollapse || !dx.isFinite() || !dy.isFinite()) return
        if (!density.isFinite() || density <= 0f || thresholdDp <= 0.0) return
        if (this.axis != axis) {
            val wasExpanded = expanded
            reset(wasExpanded)
            this.axis = axis
        }
        val px = dx / density
        val py = dy / density
        if (axis != Axis.FREE) {
            val forward = if (axis == Axis.VERTICAL) -py else -px
            reverseTravel = (reverseTravel + if (expanded) forward else -forward).coerceAtLeast(0.0)
            if (reverseTravel >= thresholdDp) {
                expanded = !expanded
                reverseTravel = 0.0
            }
            return
        }
        if (expanded) {
            x += px
            y += py
            val distance = hypot(x, y)
            if (distance >= thresholdDp) {
                directionX = x / distance
                directionY = y / distance
                expanded = false
                x = 0.0
                y = 0.0
                reverseTravel = 0.0
            }
        } else {
            // Measure reversal from the latest furthest point, not the start of a long gesture.
            // Manual collapse has no direction and waits for the explicit expand action.
            reverseTravel = (reverseTravel - px * directionX - py * directionY).coerceAtLeast(0.0)
            if (reverseTravel >= thresholdDp) reset()
        }
    }
}
