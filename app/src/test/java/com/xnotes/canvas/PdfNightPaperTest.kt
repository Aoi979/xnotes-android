package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfNightPaperTest {
    @Test fun loadingPdfUsesNightPaperWithoutChangingBlankPagesOrDocumentStyles() {
        val doc = Document.blank(2)
        val pdf = doc.pages[0].apply { pdfPage = 0; style = PageStyle(pageColor = Rgba(255, 255, 255)) }
        val blank = doc.pages[1].apply { style = PageStyle(pageColor = Rgba(250, 240, 220)) }
        val state = CanvasState(doc, FakeSurfaceFactory(), Palette.DEFAULT)
        val dirtyBefore = doc.dirty
        state.pdfPaperColor = ViewSettings.NIGHT_PAPER
        assertEquals(ViewSettings.NIGHT_PAPER, state.paperColor(pdf))
        assertEquals(Rgba(250, 240, 220), state.paperColor(blank))
        assertEquals(Rgba(255, 255, 255), pdf.style.pageColor)
        assertEquals(dirtyBefore, doc.dirty)
        state.pdfPaperColor = null
        assertEquals(Rgba(255, 255, 255), state.paperColor(pdf))
    }
}
