package com.xnotes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarMotionTest {
    private fun motion() = ToolbarMotion().apply { enabled = true }
    private fun ToolbarMotion.vertical(dy: Double, density: Float = 1f) = pan(0.0, dy, ToolbarMotion.Axis.VERTICAL, density)
    private fun ToolbarMotion.free(dx: Double, dy: Double) = pan(dx, dy, ToolbarMotion.Axis.FREE, 1f)

    @Test fun noteRequiresFortyDpAndOnlyReadingAxisCounts() {
        val m = motion()
        m.pan(-100.0, 0.0, ToolbarMotion.Axis.VERTICAL, 1f)
        m.vertical(-79.0, 2f)
        assertTrue(m.expanded)
        m.vertical(-1.0, 2f)
        assertFalse(m.expanded)
        m.vertical(79.0, 2f)
        assertFalse(m.expanded)
        m.vertical(1.0, 2f)
        assertTrue(m.expanded)
    }

    @Test fun horizontalPagesCollapseForwardAndExpandBack() {
        val m = motion()
        m.pan(-40.0, 100.0, ToolbarMotion.Axis.HORIZONTAL, 1f)
        assertFalse(m.expanded)
        m.pan(40.0, 0.0, ToolbarMotion.Axis.HORIZONTAL, 1f)
        assertTrue(m.expanded)
    }

    @Test fun reversalMeasuredFromLatestPointOfLongPan() {
        val m = motion()
        m.vertical(-200.0)
        m.vertical(-2000.0)
        m.vertical(39.0)
        assertFalse(m.expanded)
        m.vertical(1.0)
        assertTrue(m.expanded)
    }

    @Test fun canvasWorksInEveryDirectionIncludingDiagonals() {
        for ((x, y) in listOf(40.0 to 0.0, -40.0 to 0.0, 0.0 to 40.0, 0.0 to -40.0, 24.0 to 32.0)) {
            val m = motion()
            m.free(x, y)
            assertFalse(m.expanded)
            m.free(x * 50, y * 50)
            m.free(-x * 0.975, -y * 0.975)
            assertFalse(m.expanded)
            m.free(-x * 0.025, -y * 0.025)
            assertTrue(m.expanded)
        }
    }

    @Test fun canvasJitterCancelsAndPerpendicularPanDoesNotExpand() {
        val m = motion()
        repeat(20) { m.free(10.0, 5.0); m.free(-10.0, -5.0) }
        assertTrue(m.expanded)
        m.free(40.0, 0.0)
        m.free(0.0, 400.0)
        assertFalse(m.expanded)
    }

    @Test fun manualToggleAndNewDocumentDiscardPreviousTravel() {
        val m = motion()
        m.free(39.0, 0.0)
        m.toggle()
        m.free(-400.0, 0.0)
        assertFalse(m.expanded)
        m.toggle()
        m.free(1.0, 0.0)
        assertTrue(m.expanded)
        m.free(40.0, 0.0)
        m.reset()
        assertTrue(m.expanded)
    }

    @Test fun disabledAccessibilityAndInvalidSamplesDoNotCollapse() {
        val m = motion()
        m.enabled = false
        m.vertical(-400.0)
        m.enabled = true
        m.allowAutoCollapse = false
        m.vertical(-400.0)
        m.allowAutoCollapse = true
        m.vertical(Double.NaN)
        m.vertical(Double.NEGATIVE_INFINITY)
        m.vertical(-400.0, 0f)
        m.vertical(-400.0, Float.NaN)
        assertTrue(m.expanded)
        m.vertical(-40.0)
        assertFalse(m.expanded)
    }

    @Test fun changingReadingAxisDiscardsOldPartialGesture() {
        val m = motion()
        m.vertical(-39.0)
        m.pan(-1.0, 0.0, ToolbarMotion.Axis.HORIZONTAL, 1f)
        assertTrue(m.expanded)
        m.pan(-39.0, 0.0, ToolbarMotion.Axis.HORIZONTAL, 1f)
        assertFalse(m.expanded)
    }
}
