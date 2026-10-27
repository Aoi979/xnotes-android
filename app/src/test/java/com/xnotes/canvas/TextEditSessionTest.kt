package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Compose must reset input state on a new edit, even if null and the next field arrive in one frame. */
class TextEditSessionTest {
    private val measurer = FakeTextMeasurer()
    private val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(20.0, 20.0), Pt(180.0, 120.0), Rgba(0, 0, 0)).apply {
        label = ShapeLabel("First", TextStyle(Rgba(0, 0, 0), 12.0, FontFace.SANS), measurer)
    }
    private val state = CanvasState(
        Document(mutableListOf(Page(400.0, 600.0, mutableListOf(shape)))),
        FakeSurfaceFactory(), Palette.DEFAULT,
    ).apply { viewportW = 800; viewportH = 1200; relayout() }
    private val controller = InteractionController(state, History(), measurer, requestRender = {})

    private fun openLabel(): EditingField {
        controller.selectAll()
        controller.labelSelection()
        return requireNotNull(controller.editingField())
    }

    @Test fun typingStylingAndPanningKeepTheSameSession() {
        val first = openLabel()
        controller.updateEditingText("Typed")
        controller.setTextFace(FontFace.MONO)
        controller.panWhileEditing(15.0, 20.0)
        val moved = requireNotNull(controller.editingField())
        assertSame(first.sessionKey, moved.sessionKey)
        assertEquals("Typed", moved.text)
        assertEquals(FontFace.MONO, moved.face)
    }

    @Test fun reopeningTheSameLabelStartsANewSession() {
        val first = openLabel()
        controller.commitTextEdit("Saved")
        val next = openLabel()
        assertNotSame(first.sessionKey, next.sessionKey)
        assertEquals("Saved", next.text)
    }

    @Test fun switchingToAnotherLabelStartsANewSession() {
        val first = openLabel()
        controller.commitTextEdit()
        val style = shape.label!!.style
        val nextShape = ShapeItem(ShapeKind.RECTANGLE, Pt(30.0, 30.0), Pt(200.0, 140.0), Rgba(0, 0, 0)).apply {
            label = ShapeLabel("Second", style, measurer)
        }
        state.document.pages[0].items.apply { clear(); add(nextShape) }
        val next = openLabel()
        assertNotSame(first.sessionKey, next.sessionKey)
        assertEquals("Second", next.text)
    }
}
